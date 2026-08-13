package com.portfolio.cairn.engine;

import com.portfolio.cairn.engine.evict.EvictionPolicy;
import com.portfolio.cairn.exception.EvictionFailedException;
import com.portfolio.cairn.exception.InvalidTtlException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.portfolio.cairn.metrics.CacheMetricsCollector;
import jakarta.annotation.PreDestroy;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class CacheEngine {
    private final ConcurrentHashMap<String, CacheEntry> store = new ConcurrentHashMap<>();
    private final EvictionPolicy evictionPolicy;
    private final int maxCapacity;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final LongAdder ttlEvictions = new LongAdder();
    
    private final MockDatabase mockDatabase;
    private final LinkedBlockingQueue<WriteEvent> writeBackQueue = new LinkedBlockingQueue<>();
    private ExecutorService writeBackExecutor;
    private final CacheMetricsCollector metricsCollector;

    @Autowired
    public CacheEngine(
            EvictionPolicy evictionPolicy,
            @Value("${cairn.cache.max-size:10000}") int maxCapacity,
            MockDatabase mockDatabase,
            CacheMetricsCollector metricsCollector
    ) {
        this.evictionPolicy = evictionPolicy;
        this.maxCapacity = maxCapacity;
        this.mockDatabase = mockDatabase;
        this.metricsCollector = metricsCollector;
        startWriteBackWorker();
    }

    public CacheEngine(EvictionPolicy evictionPolicy, int maxCapacity, MockDatabase mockDatabase) {
        this(evictionPolicy, maxCapacity, mockDatabase, new CacheMetricsCollector(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    public CacheEngine(EvictionPolicy evictionPolicy, int maxCapacity) {
        this(evictionPolicy, maxCapacity, new MockDatabase());
    }

    private void startWriteBackWorker() {
        writeBackExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "cairn-write-back-worker");
            t.setDaemon(true);
            return t;
        });
        writeBackExecutor.submit(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    WriteEvent event = writeBackQueue.take();
                    try {
                        mockDatabase.save(event.key(), event.value());
                    } catch (Exception e) {
                        // Suppress background exception to maintain stability
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @PreDestroy
    public void stopWriteBackWorker() {
        if (writeBackExecutor != null) {
            writeBackExecutor.shutdownNow();
        }
    }

    /**
     * Checks if a key exists in the cache.
     * Fast boolean lookup with passive expiration checks.
     */
    public boolean exists(String key) {
        CacheEntry entry = store.get(key);
        if (entry != null) {
            if (System.currentTimeMillis() > entry.expiryTime()) {
                writeLock.lock();
                try {
                    CacheEntry currentEntry = store.get(key);
                    if (currentEntry != null && System.currentTimeMillis() > currentEntry.expiryTime()) {
                        store.remove(key);
                        evictionPolicy.onRemove(key);
                        ttlEvictions.increment();
                    }
                } finally {
                    writeLock.unlock();
                }
                return false;
            }
            return true;
        }
        return false;
    }

    /**
     * Retrieve operation returning the CacheEntry wrapper and triggering policy promotion,
     * including passive expiration checks.
     */
    public CacheEntry get(String key) {
        long start = System.nanoTime();
        try {
            CacheEntry entry = store.get(key);
            if (entry != null) {
                if (System.currentTimeMillis() > entry.expiryTime()) {
                    writeLock.lock();
                    try {
                        CacheEntry currentEntry = store.get(key);
                        if (currentEntry != null && System.currentTimeMillis() > currentEntry.expiryTime()) {
                            store.remove(key);
                            evictionPolicy.onRemove(key);
                            ttlEvictions.increment();
                            metricsCollector.recordTtlEviction();
                        }
                    } finally {
                        writeLock.unlock();
                    }
                    metricsCollector.recordMiss();
                    return null;
                }
                evictionPolicy.onAccess(key);
                metricsCollector.recordHit();
            } else {
                metricsCollector.recordMiss();
            }
            return entry;
        } finally {
            metricsCollector.recordLatency(System.nanoTime() - start);
        }
    }

    /**
     * Insert operation with no expiry (defaults to Long.MAX_VALUE).
     */
    public void set(String key, String value) {
        set(key, value, null);
    }

    /**
     * Insert/update operation with a TTL in seconds.
     * If the cache size exceeds maxCapacity, the active policy's evictVictim() is triggered.
     */
    public void set(String key, String value, Long ttlSeconds) {
        if (ttlSeconds != null && ttlSeconds <= 0) {
            throw new InvalidTtlException("TTL must be a positive integer.");
        }
        long start = System.nanoTime();
        try {
            writeLock.lock();
            try {
                long expiryTime = (ttlSeconds == null) ? Long.MAX_VALUE : (System.currentTimeMillis() + ttlSeconds * 1000);
                boolean isUpdate = store.containsKey(key);

                if (!isUpdate && store.size() >= maxCapacity) {
                    String victim = evictionPolicy.evictVictim();
                    if (victim == null) {
                        throw new EvictionFailedException("Cache capacity reached and eviction was unable to free memory.");
                    }
                    store.remove(victim);
                    metricsCollector.recordPolicyEviction();
                }

                CacheEntry entry = new CacheEntry(value, System.currentTimeMillis(), expiryTime, System.currentTimeMillis(), 1);
                store.put(key, entry);

                if (isUpdate) {
                    evictionPolicy.onAccess(key);
                } else {
                    evictionPolicy.onInsert(key);
                }
            } finally {
                writeLock.unlock();
            }
        } finally {
            metricsCollector.recordLatency(System.nanoTime() - start);
        }
    }

    /**
     * Delete operation. Returns the deleted CacheEntry if existed, else null.
     */
    public CacheEntry delete(String key) {
        long start = System.nanoTime();
        try {
            writeLock.lock();
            try {
                CacheEntry entry = store.remove(key);
                if (entry != null) {
                    evictionPolicy.onRemove(key);
                }
                return entry;
            } finally {
                writeLock.unlock();
            }
        } finally {
            metricsCollector.recordLatency(System.nanoTime() - start);
        }
    }

    /**
     * Evicts the key if it has expired. Used primarily by the background sweep.
     * Returns true if evicted, false otherwise.
     */
    public boolean evictIfExpired(String key) {
        writeLock.lock();
        try {
            CacheEntry entry = store.get(key);
            if (entry != null && System.currentTimeMillis() > entry.expiryTime()) {
                store.remove(key);
                evictionPolicy.onRemove(key);
                ttlEvictions.increment();
                metricsCollector.recordTtlEviction();
                return true;
            }
        } finally {
            writeLock.unlock();
        }
        return false;
    }

    /**
     * Returns a weakly-consistent iterator over the keys in the cache store.
     */
    public Iterator<String> getKeysIterator() {
        return store.keySet().iterator();
    }

    /**
     * Clear the cache and remove keys from the eviction policy tracking.
     */
    public void clear() {
        writeLock.lock();
        try {
            for (String key : store.keySet()) {
                evictionPolicy.onRemove(key);
            }
            store.clear();
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Returns the current key count in the cache.
     */
    public int size() {
        return store.size();
    }

    /**
     * Returns the maximum capacity of the cache.
     */
    public int getMaxCapacity() {
        return maxCapacity;
    }

    /**
     * Returns the total count of key-level TTL expirations.
     */
    public long getTtlEvictions() {
        return ttlEvictions.sum();
    }

    /**
     * Write-through semantic: writes to local cache, then synchronously writes to MockDatabase.
     * Fails if DB write fails.
     */
    public void writeThrough(String key, String value) {
        set(key, value);
        mockDatabase.save(key, value);
    }

    /**
     * Write-back semantic: writes to local cache, appends to queue, returns immediately.
     */
    public void writeBack(String key, String value) {
        set(key, value);
        writeBackQueue.offer(new WriteEvent(key, value));
    }

    /**
     * Returns the size of the write-back queue.
     */
    public int getWriteBackQueueSize() {
        return writeBackQueue.size();
    }

    public static record WriteEvent(String key, String value) {}
}

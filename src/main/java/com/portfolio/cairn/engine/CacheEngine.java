package com.portfolio.cairn.engine;

import com.portfolio.cairn.engine.evict.EvictionPolicy;
import com.portfolio.cairn.exception.EvictionFailedException;
import com.portfolio.cairn.exception.InvalidTtlException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

@Service
public class CacheEngine {
    private final ConcurrentHashMap<String, CacheEntry> store = new ConcurrentHashMap<>();
    private final EvictionPolicy evictionPolicy;
    private final int maxCapacity;
    private final Object writeLock = new Object();
    private final LongAdder ttlEvictions = new LongAdder();

    @Autowired
    public CacheEngine(
            EvictionPolicy evictionPolicy,
            @Value("${cairn.cache.max-size:10000}") int maxCapacity
    ) {
        this.evictionPolicy = evictionPolicy;
        this.maxCapacity = maxCapacity;
    }

    /**
     * Checks if a key exists in the cache.
     * Fast boolean lookup with passive expiration checks.
     */
    public boolean exists(String key) {
        CacheEntry entry = store.get(key);
        if (entry != null) {
            if (System.currentTimeMillis() > entry.expiryTime()) {
                synchronized (writeLock) {
                    CacheEntry currentEntry = store.get(key);
                    if (currentEntry != null && System.currentTimeMillis() > currentEntry.expiryTime()) {
                        store.remove(key);
                        evictionPolicy.onRemove(key);
                        ttlEvictions.increment();
                    }
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
        CacheEntry entry = store.get(key);
        if (entry != null) {
            if (System.currentTimeMillis() > entry.expiryTime()) {
                synchronized (writeLock) {
                    CacheEntry currentEntry = store.get(key);
                    if (currentEntry != null && System.currentTimeMillis() > currentEntry.expiryTime()) {
                        store.remove(key);
                        evictionPolicy.onRemove(key);
                        ttlEvictions.increment();
                    }
                }
                return null;
            }
            evictionPolicy.onAccess(key);
        }
        return entry;
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
        synchronized (writeLock) {
            long expiryTime = (ttlSeconds == null) ? Long.MAX_VALUE : (System.currentTimeMillis() + ttlSeconds * 1000);
            boolean isUpdate = store.containsKey(key);

            if (!isUpdate && store.size() >= maxCapacity) {
                String victim = evictionPolicy.evictVictim();
                if (victim == null) {
                    throw new EvictionFailedException("Cache capacity reached and eviction was unable to free memory.");
                }
                store.remove(victim);
            }

            CacheEntry entry = new CacheEntry(value, System.currentTimeMillis(), expiryTime, System.currentTimeMillis(), 1);
            store.put(key, entry);

            if (isUpdate) {
                evictionPolicy.onAccess(key);
            } else {
                evictionPolicy.onInsert(key);
            }
        }
    }

    /**
     * Delete operation. Returns the deleted CacheEntry if existed, else null.
     */
    public CacheEntry delete(String key) {
        synchronized (writeLock) {
            CacheEntry entry = store.remove(key);
            if (entry != null) {
                evictionPolicy.onRemove(key);
            }
            return entry;
        }
    }

    /**
     * Evicts the key if it has expired. Used primarily by the background sweep.
     * Returns true if evicted, false otherwise.
     */
    public boolean evictIfExpired(String key) {
        synchronized (writeLock) {
            CacheEntry entry = store.get(key);
            if (entry != null && System.currentTimeMillis() > entry.expiryTime()) {
                store.remove(key);
                evictionPolicy.onRemove(key);
                ttlEvictions.increment();
                return true;
            }
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
        synchronized (writeLock) {
            for (String key : store.keySet()) {
                evictionPolicy.onRemove(key);
            }
            store.clear();
        }
    }

    /**
     * Returns the current key count in the cache.
     */
    public int size() {
        return store.size();
    }

    /**
     * Returns the total count of key-level TTL expirations.
     */
    public long getTtlEvictions() {
        return ttlEvictions.sum();
    }
}

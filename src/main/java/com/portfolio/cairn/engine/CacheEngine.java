package com.portfolio.cairn.engine;

import com.portfolio.cairn.engine.evict.EvictionPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CacheEngine {
    private final ConcurrentHashMap<String, CacheEntry> store = new ConcurrentHashMap<>();
    private final EvictionPolicy evictionPolicy;
    private final int maxCapacity;
    private final Object writeLock = new Object();

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
     * Fast boolean lookup with no side effects on access metadata (no promotion).
     */
    public boolean exists(String key) {
        return store.containsKey(key);
    }

    /**
     * Retrieve operation returning the CacheEntry wrapper and triggering policy promotion.
     */
    public CacheEntry get(String key) {
        CacheEntry entry = store.get(key);
        if (entry != null) {
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
        synchronized (writeLock) {
            long expiryTime = (ttlSeconds == null) ? Long.MAX_VALUE : (System.currentTimeMillis() + ttlSeconds * 1000);
            boolean isUpdate = store.containsKey(key);

            if (!isUpdate && store.size() >= maxCapacity) {
                String victim = evictionPolicy.evictVictim();
                if (victim != null) {
                    store.remove(victim);
                }
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
}

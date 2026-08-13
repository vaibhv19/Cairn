package com.portfolio.cairn.engine;

import org.springframework.stereotype.Service;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CacheEngine {
    private final ConcurrentHashMap<String, CacheEntry> store = new ConcurrentHashMap<>();

    /**
     * Checks if a key exists in the cache.
     * Fast boolean lookup with no side effects on access metadata (no promotion).
     */
    public boolean exists(String key) {
        return store.containsKey(key);
    }

    /**
     * Basic retrieve operation returning the CacheEntry wrapper.
     */
    public CacheEntry get(String key) {
        return store.get(key);
    }

    /**
     * Basic insert operation with no expiry (defaults to Long.MAX_VALUE).
     */
    public void set(String key, String value) {
        store.put(key, new CacheEntry(value, System.currentTimeMillis()));
    }

    /**
     * Basic insert operation with explicit expiration timestamp.
     */
    public void set(String key, String value, long expiryTime) {
        store.put(key, new CacheEntry(value, System.currentTimeMillis(), expiryTime, System.currentTimeMillis(), 1));
    }

    /**
     * Basic delete operation. Returns the deleted CacheEntry if existed, else null.
     */
    public CacheEntry delete(String key) {
        return store.remove(key);
    }

    /**
     * Clear the cache.
     */
    public void clear() {
        store.clear();
    }

    /**
     * Returns the current key count in the cache.
     */
    public int size() {
        return store.size();
    }
}

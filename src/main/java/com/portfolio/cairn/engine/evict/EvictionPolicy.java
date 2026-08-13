package com.portfolio.cairn.engine.evict;

/**
 * Strategy interface defining the contract for pluggable eviction policies.
 */
public interface EvictionPolicy {
    
    /**
     * Invoked when an existing key is accessed (read/hit).
     */
    void onAccess(String key);

    /**
     * Invoked when a new key is written to the cache.
     */
    void onInsert(String key);

    /**
     * Invoked when a key is explicitly deleted or expired.
     */
    void onRemove(String key);

    /**
     * Selects and expels the victim key based on policy logic (e.g. LRU or LFU).
     * @return the key of the evicted entry, or null if the cache is empty.
     */
    String evictVictim();
}

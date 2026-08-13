package com.portfolio.cairn.engine;

public record CacheEntry(
    String value,
    long createdTime,
    long expiryTime,
    long lastAccessTime,
    int accessFrequency
) {
    /**
     * Overloaded constructor that defaults expiryTime to Long.MAX_VALUE when unset.
     * It also sets lastAccessTime to createdTime and accessFrequency to 1.
     */
    public CacheEntry(String value, long createdTime) {
        this(value, createdTime, Long.MAX_VALUE, createdTime, 1);
    }

    /**
     * Overloaded constructor to allow setting a specific TTL at creation.
     */
    public CacheEntry(String value, long createdTime, long expiryTime) {
        this(value, createdTime, expiryTime, createdTime, 1);
    }

    /**
     * Returns a new CacheEntry with updated access metadata.
     */
    public CacheEntry withAccess(long accessTime) {
        return new CacheEntry(value, createdTime, expiryTime, accessTime, accessFrequency + 1);
    }

    /**
     * Returns a new CacheEntry with an updated expiration time.
     */
    public CacheEntry withExpiry(long newExpiryTime) {
        return new CacheEntry(value, createdTime, newExpiryTime, lastAccessTime, accessFrequency);
    }
}

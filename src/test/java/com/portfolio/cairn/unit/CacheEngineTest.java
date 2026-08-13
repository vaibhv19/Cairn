package com.portfolio.cairn.unit;

import com.portfolio.cairn.engine.CacheEngine;
import com.portfolio.cairn.engine.CacheEntry;
import com.portfolio.cairn.engine.evict.LruEvictionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class CacheEngineTest {

    private CacheEngine cacheEngine;

    @BeforeEach
    public void setUp() {
        cacheEngine = new CacheEngine(new LruEvictionPolicy(), 10000);
    }

    @Test
    public void testInsertRetrieveDelete() {
        // Given
        String key = "testKey";
        String value = "testValue";

        // When (Insert)
        cacheEngine.set(key, value);

        // Then (Retrieve)
        assertThat(cacheEngine.exists(key)).isTrue();
        CacheEntry entry = cacheEngine.get(key);
        assertThat(entry).isNotNull();
        assertThat(entry.value()).isEqualTo(value);

        // When (Delete)
        CacheEntry deletedEntry = cacheEngine.delete(key);

        // Then (Verify Deleted)
        assertThat(cacheEngine.exists(key)).isFalse();
        assertThat(cacheEngine.get(key)).isNull();
        assertThat(deletedEntry).isNotNull();
        assertThat(deletedEntry.value()).isEqualTo(value);
    }

    @Test
    public void testExistsDoesNotMutateMetadata() {
        // Given
        String key = "metaKey";
        String value = "metaValue";
        cacheEngine.set(key, value);

        CacheEntry before = cacheEngine.get(key);
        long originalAccessTime = before.lastAccessTime();
        int originalFrequency = before.accessFrequency();

        // When (Check Exists)
        boolean existsResult = cacheEngine.exists(key);

        // Then
        assertThat(existsResult).isTrue();
        CacheEntry after = cacheEngine.get(key);
        assertThat(after.lastAccessTime()).isEqualTo(originalAccessTime);
        assertThat(after.accessFrequency()).isEqualTo(originalFrequency);
    }

    @Test
    public void testCacheEntryDefaultExpiry() {
        // Given
        String value = "noTtlValue";
        long creationTime = System.currentTimeMillis();

        // When
        CacheEntry entry = new CacheEntry(value, creationTime);

        // Then
        assertThat(entry.expiryTime()).isEqualTo(Long.MAX_VALUE);
        assertThat(entry.lastAccessTime()).isEqualTo(creationTime);
        assertThat(entry.accessFrequency()).isEqualTo(1);
    }

    @Test
    public void testCacheEntryOverloadedExpiry() {
        // Given
        String value = "ttlValue";
        long creationTime = System.currentTimeMillis();
        long expiryTime = creationTime + 5000;

        // When
        CacheEntry entry = new CacheEntry(value, creationTime, expiryTime);

        // Then
        assertThat(entry.expiryTime()).isEqualTo(expiryTime);
        assertThat(entry.lastAccessTime()).isEqualTo(creationTime);
        assertThat(entry.accessFrequency()).isEqualTo(1);
    }

    @Test
    public void testCacheEntryHelperMethods() {
        // Given
        CacheEntry entry = new CacheEntry("val", System.currentTimeMillis());
        long newAccessTime = System.currentTimeMillis() + 100;
        long newExpiryTime = System.currentTimeMillis() + 10000;

        // When
        CacheEntry accessed = entry.withAccess(newAccessTime);
        CacheEntry updatedExpiry = entry.withExpiry(newExpiryTime);

        // Then
        assertThat(accessed.accessFrequency()).isEqualTo(2);
        assertThat(accessed.lastAccessTime()).isEqualTo(newAccessTime);
        assertThat(accessed.expiryTime()).isEqualTo(entry.expiryTime());

        assertThat(updatedExpiry.expiryTime()).isEqualTo(newExpiryTime);
        assertThat(updatedExpiry.accessFrequency()).isEqualTo(1);
    }
}

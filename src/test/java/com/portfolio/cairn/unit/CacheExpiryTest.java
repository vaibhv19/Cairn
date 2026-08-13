package com.portfolio.cairn.unit;

import com.portfolio.cairn.engine.CacheEngine;
import com.portfolio.cairn.engine.evict.LruEvictionPolicy;
import com.portfolio.cairn.expire.ActiveExpirySweeper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class CacheExpiryTest {

    private CacheEngine cacheEngine;
    private ActiveExpirySweeper sweeper;

    @BeforeEach
    public void setUp() {
        cacheEngine = new CacheEngine(new LruEvictionPolicy(), 100);
        sweeper = new ActiveExpirySweeper(cacheEngine, 5000);
    }

    @Test
    public void testPassiveExpiration() throws InterruptedException {
        // Given: Insert k1 with 1s TTL, and k2 without TTL
        cacheEngine.set("k1", "v1", 1L); // 1 second
        cacheEngine.set("k2", "v2"); // No TTL

        assertThat(cacheEngine.exists("k1")).isTrue();
        assertThat(cacheEngine.get("k1")).isNotNull();
        assertThat(cacheEngine.exists("k2")).isTrue();

        // When: Waiting 1.5 seconds for k1 to expire
        Thread.sleep(1500);

        // Then: k1 must passive expire, k2 must remain
        assertThat(cacheEngine.exists("k1")).isFalse(); // triggers passive cleanup
        assertThat(cacheEngine.get("k1")).isNull();
        assertThat(cacheEngine.exists("k2")).isTrue();
        assertThat(cacheEngine.getTtlEvictions()).isEqualTo(1L);
    }

    @Test
    public void testActiveSweeperAdaptiveLoop() throws InterruptedException {
        // Given: Insert 20 keys with 1s TTL
        for (int i = 0; i < 20; i++) {
            cacheEngine.set("key-" + i, "val-" + i, 1L);
        }

        // Initially no expired keys
        sweeper.sweep();
        assertThat(cacheEngine.size()).isEqualTo(20);

        // Wait 1.5s for keys to expire
        Thread.sleep(1500);

        // When: We sweep. The batch of 20 keys is checked. All 20 are expired (100% ratio).
        // Since 100% > 25%, the loop runs again. All 20 keys are cleared.
        sweeper.sweep();

        // Then: Cache size should be 0, and ttlEvictions should be 20
        assertThat(cacheEngine.size()).isEqualTo(0);
        assertThat(cacheEngine.getTtlEvictions()).isEqualTo(20L);
    }
}

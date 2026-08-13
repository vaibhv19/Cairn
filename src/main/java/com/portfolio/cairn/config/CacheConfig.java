package com.portfolio.cairn.config;

import com.portfolio.cairn.engine.evict.EvictionPolicy;
import com.portfolio.cairn.engine.evict.LfuEvictionPolicy;
import com.portfolio.cairn.engine.evict.LruEvictionPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CacheConfig {

    @Bean
    @ConditionalOnProperty(name = "cairn.cache.eviction-policy", havingValue = "lru", matchIfMissing = true)
    public EvictionPolicy lruEvictionPolicy() {
        return new LruEvictionPolicy();
    }

    @Bean
    @ConditionalOnProperty(name = "cairn.cache.eviction-policy", havingValue = "lfu")
    public EvictionPolicy lfuEvictionPolicy() {
        return new LfuEvictionPolicy();
    }
}

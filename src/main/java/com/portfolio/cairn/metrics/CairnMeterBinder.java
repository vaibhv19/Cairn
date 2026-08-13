package com.portfolio.cairn.metrics;

import com.portfolio.cairn.engine.CacheEngine;
import com.portfolio.cairn.engine.evict.EvictionPolicy;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Micrometer MeterBinder registration to expose Cairn cache metrics under
 * 'cairn.cache' in Spring Boot Actuator with the exact statistic and eviction policy tags.
 */
@Component
public class CairnMeterBinder implements MeterBinder {

    private final CacheMetricsCollector collector;
    private final CacheEngine cacheEngine;
    private final EvictionPolicy evictionPolicy;

    @Autowired
    public CairnMeterBinder(
            CacheMetricsCollector collector,
            CacheEngine cacheEngine,
            EvictionPolicy evictionPolicy
    ) {
        this.collector = collector;
        this.cacheEngine = cacheEngine;
        this.evictionPolicy = evictionPolicy;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        String policyName = evictionPolicy.getClass().getSimpleName()
                .replace("EvictionPolicy", "")
                .toLowerCase();

        // Bind Hits
        FunctionCounter.builder("cairn.cache", collector, CacheMetricsCollector::getHits)
                .description("Cairn cache hits")
                .tag("statistic", "HITS")
                .tag("eviction.policy", policyName)
                .register(registry);

        // Bind Misses
        FunctionCounter.builder("cairn.cache", collector, CacheMetricsCollector::getMisses)
                .description("Cairn cache misses")
                .tag("statistic", "MISSES")
                .tag("eviction.policy", policyName)
                .register(registry);

        // Bind Policy Evictions
        FunctionCounter.builder("cairn.cache", collector, CacheMetricsCollector::getPolicyEvictions)
                .description("Cairn cache policy evictions")
                .tag("statistic", "EVICTIONS_POLICY")
                .tag("eviction.policy", policyName)
                .register(registry);

        // Bind TTL Evictions
        FunctionCounter.builder("cairn.cache", collector, CacheMetricsCollector::getTtlEvictions)
                .description("Cairn cache TTL evictions")
                .tag("statistic", "EVICTIONS_TTL")
                .tag("eviction.policy", policyName)
                .register(registry);

        // Bind Key Count Gauge
        Gauge.builder("cairn.cache", cacheEngine, CacheEngine::size)
                .description("Cairn cache active key count")
                .tag("statistic", "COUNT_KEYS")
                .tag("eviction.policy", policyName)
                .register(registry);
    }
}

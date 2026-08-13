package com.portfolio.cairn.expire;

import com.portfolio.cairn.engine.CacheEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class ActiveExpirySweeper {

    private static final Logger logger = LoggerFactory.getLogger(ActiveExpirySweeper.class);

    private final CacheEngine cacheEngine;
    private final long sweepIntervalMs;
    private ScheduledExecutorService scheduler;

    @Autowired
    public ActiveExpirySweeper(
            CacheEngine cacheEngine,
            @Value("${cairn.cache.sweep-interval-ms:5000}") long sweepIntervalMs
    ) {
        this.cacheEngine = cacheEngine;
        this.sweepIntervalMs = sweepIntervalMs;
    }

    @PostConstruct
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cairn-expiry-sweeper");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::sweep, sweepIntervalMs, sweepIntervalMs, TimeUnit.MILLISECONDS);
        logger.info("ActiveExpirySweeper started with interval {} ms", sweepIntervalMs);
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        logger.info("ActiveExpirySweeper stopped");
    }

    /**
     * Executes the adaptive sweep logic.
     */
    public void sweep() {
        int batchSize = 20;
        boolean adaptiveLoop = true;

        while (adaptiveLoop) {
            List<String> sample = sampleKeys(batchSize);
            if (sample.isEmpty()) {
                break;
            }

            int expiredCount = 0;
            for (String key : sample) {
                if (cacheEngine.evictIfExpired(key)) {
                    expiredCount++;
                }
            }

            double expiredRatio = (double) expiredCount / sample.size();
            // If more than 25% of keys are expired, sweep again immediately
            if (expiredRatio > 0.25) {
                adaptiveLoop = true;
            } else {
                adaptiveLoop = false;
            }
        }
    }

    private List<String> sampleKeys(int batchSize) {
        List<String> sample = new ArrayList<>(batchSize);
        Iterator<String> iterator = cacheEngine.getKeysIterator();
        int count = 0;
        while (iterator.hasNext() && count < batchSize) {
            sample.add(iterator.next());
            count++;
        }
        return sample;
    }
}

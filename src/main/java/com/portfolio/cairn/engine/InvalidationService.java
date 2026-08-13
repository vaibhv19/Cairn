package com.portfolio.cairn.engine;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Iterator;

/**
 * Service to handle explicit key and pattern-based cache invalidations.
 */
@Service
public class InvalidationService {

    private final CacheEngine cacheEngine;

    @Autowired
    public InvalidationService(CacheEngine cacheEngine) {
        this.cacheEngine = cacheEngine;
    }

    /**
     * Invalidates keys in the cache matching the given pattern.
     * Supports exact-key match, wildcard prefix match (e.g. user:*), and full flush (*).
     * Returns the count of invalidated keys.
     */
    public int invalidateByPattern(String pattern) {
        if (pattern == null || pattern.trim().isEmpty()) {
            return 0;
        }

        if (pattern.equals("*")) {
            int size = cacheEngine.size();
            cacheEngine.clear();
            return size;
        }

        if (pattern.endsWith("*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            int invalidatedCount = 0;
            Iterator<String> iterator = cacheEngine.getKeysIterator();
            while (iterator.hasNext()) {
                String key = iterator.next();
                if (key.startsWith(prefix)) {
                    CacheEntry deleted = cacheEngine.delete(key);
                    if (deleted != null) {
                        invalidatedCount++;
                    }
                }
            }
            return invalidatedCount;
        }

        // Exact match
        CacheEntry deleted = cacheEngine.delete(pattern);
        return deleted != null ? 1 : 0;
    }
}

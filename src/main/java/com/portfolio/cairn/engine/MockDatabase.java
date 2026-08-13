package com.portfolio.cairn.engine;

import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory simulated external database for testing write-through and write-back semantics.
 */
@Component
public class MockDatabase {

    private final Map<String, String> dbStore = new ConcurrentHashMap<>();
    private volatile boolean shouldFail = false;

    public void save(String key, String value) {
        if (shouldFail) {
            throw new RuntimeException("Simulated DB Write Failure");
        }
        dbStore.put(key, value);
    }

    public String find(String key) {
        return dbStore.get(key);
    }

    public void delete(String key) {
        dbStore.remove(key);
    }

    public void setShouldFail(boolean shouldFail) {
        this.shouldFail = shouldFail;
    }

    public void clear() {
        dbStore.clear();
        shouldFail = false;
    }

    public int size() {
        return dbStore.size();
    }
}

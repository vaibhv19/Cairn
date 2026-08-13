package com.portfolio.cairn.engine.evict;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

public class LfuEvictionPolicy implements EvictionPolicy {

    private final Map<String, Integer> keyToFreq = new HashMap<>();
    private final Map<Integer, LinkedHashSet<String>> freqToKeys = new HashMap<>();
    private int minFrequency = -1;

    private final ReentrantLock lock = new ReentrantLock();

    @Override
    public void onAccess(String key) {
        lock.lock();
        try {
            if (keyToFreq.containsKey(key)) {
                promote(key);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void onInsert(String key) {
        lock.lock();
        try {
            if (keyToFreq.containsKey(key)) {
                promote(key);
            } else {
                insertNew(key);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void onRemove(String key) {
        lock.lock();
        try {
            if (keyToFreq.containsKey(key)) {
                removeKey(key);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String evictVictim() {
        lock.lock();
        try {
            if (keyToFreq.isEmpty()) {
                return null;
            }
            LinkedHashSet<String> minFreqSet = freqToKeys.get(minFrequency);
            if (minFreqSet == null || minFreqSet.isEmpty()) {
                return null;
            }
            // LinkedHashSet iterator returns elements in insertion order (FIFO/LRU behavior within same freq)
            String victimKey = minFreqSet.iterator().next();
            removeKey(victimKey);
            return victimKey;
        } finally {
            lock.unlock();
        }
    }

    // --- Helper LFU Mutations (Called under outer ReentrantLock lock) ---

    private void promote(String key) {
        int freq = keyToFreq.get(key);
        int newFreq = freq + 1;
        keyToFreq.put(key, newFreq);

        LinkedHashSet<String> oldSet = freqToKeys.get(freq);
        oldSet.remove(key);
        if (oldSet.isEmpty() && freq == minFrequency) {
            minFrequency = newFreq;
        }

        freqToKeys.computeIfAbsent(newFreq, k -> new LinkedHashSet<>()).add(key);
    }

    private void insertNew(String key) {
        keyToFreq.put(key, 1);
        freqToKeys.computeIfAbsent(1, k -> new LinkedHashSet<>()).add(key);
        minFrequency = 1;
    }

    private void removeKey(String key) {
        int freq = keyToFreq.remove(key);
        LinkedHashSet<String> set = freqToKeys.get(freq);
        if (set != null) {
            set.remove(key);
            if (set.isEmpty() && freq == minFrequency) {
                // Find next lowest frequency or reset
                if (keyToFreq.isEmpty()) {
                    minFrequency = -1;
                } else {
                    // Search for next minimum frequency
                    int nextMin = Integer.MAX_VALUE;
                    for (int f : freqToKeys.keySet()) {
                        LinkedHashSet<String> s = freqToKeys.get(f);
                        if (s != null && !s.isEmpty() && f < nextMin) {
                            nextMin = f;
                        }
                    }
                    minFrequency = nextMin;
                }
            }
        }
    }

    // --- Public helper methods for test assertions ---

    public int getMapSize() {
        lock.lock();
        try {
            return keyToFreq.size();
        } finally {
            lock.unlock();
        }
    }

    public Integer getFrequency(String key) {
        lock.lock();
        try {
            return keyToFreq.get(key);
        } finally {
            lock.unlock();
        }
    }

    public int getMinFrequency() {
        lock.lock();
        try {
            return minFrequency;
        } finally {
            lock.unlock();
        }
    }

    public boolean checkIntegrity() {
        lock.lock();
        try {
            int totalSetSize = 0;
            for (Map.Entry<Integer, LinkedHashSet<String>> entry : freqToKeys.entrySet()) {
                LinkedHashSet<String> set = entry.getValue();
                if (set != null) {
                    totalSetSize += set.size();
                    for (String key : set) {
                        if (!keyToFreq.containsKey(key)) {
                            return false; // Key in set but not in frequency map
                        }
                        if (keyToFreq.get(key) != entry.getKey()) {
                            return false; // Key in set of wrong frequency
                        }
                    }
                }
            }
            if (totalSetSize != keyToFreq.size()) {
                return false; // Mismatched sizes
            }
            if (!keyToFreq.isEmpty()) {
                LinkedHashSet<String> minSet = freqToKeys.get(minFrequency);
                if (minSet == null || minSet.isEmpty()) {
                    return false; // Invalid minFrequency
                }
            }
            return true;
        } finally {
            lock.unlock();
        }
    }
}

package com.portfolio.cairn.sharding;

import com.google.common.hash.Hashing;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Consistent Hash Ring implementation mapping keys to node IDs using virtual nodes.
 * Thread-safety is guaranteed using a ReentrantReadWriteLock.
 */
@Component
public class ConsistentHashRing {

    private final TreeMap<Long, String> ring = new TreeMap<>();
    private final Map<String, Integer> nodes = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    /**
     * Hashes a key using Murmur3_32 and pads it to an unsigned 32-bit long value.
     */
    private long hash(String key) {
        return Integer.toUnsignedLong(Hashing.murmur3_32_fixed().hashBytes(key.getBytes(StandardCharsets.UTF_8)).asInt());
    }

    /**
     * Adds a physical node to the ring by hashing its virtual nodes.
     */
    public void addNode(String nodeId, int virtualNodeCount) {
        rwLock.writeLock().lock();
        try {
            nodes.put(nodeId, virtualNodeCount);
            for (int i = 0; i < virtualNodeCount; i++) {
                long hashVal = hash(nodeId + "#" + i);
                ring.put(hashVal, nodeId);
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Removes a physical node and its virtual nodes from the ring.
     */
    public void removeNode(String nodeId, int virtualNodeCount) {
        rwLock.writeLock().lock();
        try {
            nodes.remove(nodeId);
            for (int i = 0; i < virtualNodeCount; i++) {
                long hashVal = hash(nodeId + "#" + i);
                ring.remove(hashVal);
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Resolves the target physical node ID for a given cache key.
     */
    public String getNode(String key) {
        rwLock.readLock().lock();
        try {
            if (ring.isEmpty()) {
                return null;
            }
            long hashVal = hash(key);
            SortedMap<Long, String> tailMap = ring.tailMap(hashVal);
            long nodeHash = tailMap.isEmpty() ? ring.firstKey() : tailMap.firstKey();
            return ring.get(nodeHash);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Clears all nodes from the ring.
     */
    public void clear() {
        rwLock.writeLock().lock();
        try {
            ring.clear();
            nodes.clear();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Returns the set of physical node IDs currently registered in the ring.
     */
    public Set<String> getNodes() {
        return nodes.keySet();
    }

    /**
     * Returns a copy of the underlying TreeMap representation of the ring.
     */
    public TreeMap<Long, String> getRingCopy() {
        rwLock.readLock().lock();
        try {
            return new TreeMap<>(ring);
        } finally {
            rwLock.readLock().unlock();
        }
    }
}

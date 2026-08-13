package com.portfolio.cairn.engine.evict;

import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Component("lruEvictionPolicy")
public class LruEvictionPolicy implements EvictionPolicy {

    private static class LruNode {
        String key;
        LruNode prev;
        LruNode next;

        LruNode(String key) {
            this.key = key;
        }
    }

    private final Map<String, LruNode> nodeMap = new HashMap<>();
    private LruNode head = null;
    private LruNode tail = null;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    @Override
    public void onAccess(String key) {
        lock.readLock().lock();
        try {
            LruNode node = nodeMap.get(key);
            if (node != null) {
                promote(node);
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void onInsert(String key) {
        lock.writeLock().lock();
        try {
            if (nodeMap.containsKey(key)) {
                promote(nodeMap.get(key));
            } else {
                LruNode node = new LruNode(key);
                nodeMap.put(key, node);
                addFirst(node);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void onRemove(String key) {
        lock.writeLock().lock();
        try {
            LruNode node = nodeMap.remove(key);
            if (node != null) {
                remove(node);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public String evictVictim() {
        lock.writeLock().lock();
        try {
            if (tail == null) {
                return null;
            }
            String victimKey = tail.key;
            nodeMap.remove(victimKey);
            remove(tail);
            return victimKey;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // --- Helper Doubly-Linked List Mutations (Synchronized for ReadLock promotion safety) ---

    private synchronized void promote(LruNode node) {
        if (node == head) {
            return;
        }
        removeNode(node);
        addFirstNode(node);
    }

    private synchronized void remove(LruNode node) {
        removeNode(node);
    }

    private synchronized void addFirst(LruNode node) {
        addFirstNode(node);
    }

    private void removeNode(LruNode node) {
        if (node.prev != null) {
            node.prev.next = node.next;
        } else {
            head = node.next;
        }
        if (node.next != null) {
            node.next.prev = node.prev;
        } else {
            tail = node.prev;
        }
        node.prev = null;
        node.next = null;
    }

    private void addFirstNode(LruNode node) {
        if (head == null) {
            head = node;
            tail = node;
        } else {
            node.next = head;
            head.prev = node;
            head = node;
        }
    }

    // --- Public helper methods for test assertions ---

    public synchronized String getHeadKey() {
        return head != null ? head.key : null;
    }

    public synchronized String getTailKey() {
        return tail != null ? tail.key : null;
    }

    public synchronized int getMapSize() {
        return nodeMap.size();
    }

    /**
     * Validates doubly-linked list integrity (no cycles, head-tail matches count).
     * @return true if valid, false if corrupt.
     */
    public synchronized boolean checkIntegrity() {
        int forwardCount = 0;
        LruNode current = head;
        LruNode prevNode = null;
        while (current != null) {
            forwardCount++;
            if (current.prev != prevNode) {
                return false; // Broken backward link
            }
            if (forwardCount > nodeMap.size()) {
                return false; // Cycle detected
            }
            prevNode = current;
            current = current.next;
        }
        if (prevNode != tail) {
            return false; // Last node traversed is not tail
        }

        int backwardCount = 0;
        current = tail;
        LruNode nextNode = null;
        while (current != null) {
            backwardCount++;
            if (current.next != nextNode) {
                return false; // Broken forward link
            }
            nextNode = current;
            current = current.prev;
        }
        if (nextNode != head) {
            return false; // First node backward-traversed is not head
        }

        return forwardCount == nodeMap.size() && backwardCount == nodeMap.size();
    }
}

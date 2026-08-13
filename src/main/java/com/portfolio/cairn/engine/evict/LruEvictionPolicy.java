package com.portfolio.cairn.engine.evict;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

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

    private final ReentrantLock lock = new ReentrantLock();

    @Override
    public void onAccess(String key) {
        lock.lock();
        try {
            LruNode node = nodeMap.get(key);
            if (node != null) {
                promote(node);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void onInsert(String key) {
        lock.lock();
        try {
            if (nodeMap.containsKey(key)) {
                promote(nodeMap.get(key));
            } else {
                LruNode node = new LruNode(key);
                nodeMap.put(key, node);
                addFirst(node);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void onRemove(String key) {
        lock.lock();
        try {
            LruNode node = nodeMap.remove(key);
            if (node != null) {
                remove(node);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String evictVictim() {
        lock.lock();
        try {
            if (tail == null) {
                return null;
            }
            String victimKey = tail.key;
            nodeMap.remove(victimKey);
            remove(tail);
            return victimKey;
        } finally {
            lock.unlock();
        }
    }

    // --- Helper Doubly-Linked List Mutations (Called under outer ReentrantLock lock) ---

    private void promote(LruNode node) {
        if (node == head) {
            return;
        }
        removeNode(node);
        addFirstNode(node);
    }

    private void remove(LruNode node) {
        removeNode(node);
    }

    private void addFirst(LruNode node) {
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

    public String getHeadKey() {
        lock.lock();
        try {
            return head != null ? head.key : null;
        } finally {
            lock.unlock();
        }
    }

    public String getTailKey() {
        lock.lock();
        try {
            return tail != null ? tail.key : null;
        } finally {
            lock.unlock();
        }
    }

    public int getMapSize() {
        lock.lock();
        try {
            return nodeMap.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Validates doubly-linked list integrity (no cycles, head-tail matches count).
     * @return true if valid, false if corrupt.
     */
    public boolean checkIntegrity() {
        lock.lock();
        try {
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
        } finally {
            lock.unlock();
        }
    }
}

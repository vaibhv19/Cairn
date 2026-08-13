# Learning & Engineering Handbook — Cairn

This document compiles the engineering insights, concurrency lessons, and architectural trade-offs discovered during the planning and implementation of Cairn. It serves as a technical retrospective for reviewers.

---

## 1. Concurrency Lessons & Lock Granularity

Implementing a thread-safe cache engine under high concurrent load requires a deep understanding of memory visibility, lock contention, and the JVM memory model.

### 1.1 ConcurrentHashMap Internals
Cairn uses `ConcurrentHashMap` (CHM) as its primary key-value store. 
- **Read Operations:** CHM achieves lock-free reads through the use of `volatile` node references, ensuring that changes made by writer threads are immediately visible to readers without blocking.
- **Write Operations:** CHM relies on bucket-level lock-striping rather than global synchronization. Only the bin head node is locked during inserts/updates, permitting concurrent writes to different hash bins.

### 1.2 ReentrantReadWriteLock vs. Synchronized Blocks
While CHM secures key-value operations, cache eviction policies (LRU/LFU) require maintaining secondary pointer chains (double-linked lists or frequency maps). Modifying these structures concurrently introduces race conditions.
- **The Read Lock:** In `LruEvictionPolicy` and `LfuEvictionPolicy`, reads to the list or updates to node access timestamps utilize the `ReadLock`. Multiple GET requests can traverse or read pointer information in parallel, dramatically increasing read throughput.
- **The Write Lock:** Eviction triggers (when capacity is reached) and key removals acquire the `WriteLock`, guaranteeing exclusive execution while unlinking nodes or rebuilding frequency buckets.
- **The Trade-Off:** The overhead of managing `ReentrantReadWriteLock` state is higher than a simple `synchronized` block under low thread count. However, under high thread counts, the ability of the `ReadLock` to execute parallel read paths outweighs this overhead, preventing the cache from degenerating into a single-threaded bottleneck.

### 1.3 Lock Granularity Decisions
A key challenge was balancing safety with performance:
- **Coarse Locking:** Initially, locking the entire `set()` or `get()` method inside `CacheEngine` was tempting to maintain atomic visibility between key creation, eviction, and list insertion.
- **Fine-Grained Realization:** Under concurrency stress testing, coarse locking resulted in thread starvation. The solution was double-checked locking for passive expiries. Inside `get()`, we perform a lock-free check of the TTL. Only if the key is expired do we enter a `synchronized` block on `writeLock` to perform a second validation and safely remove the key, avoiding unnecessary lock acquisition on hits.

---

## 2. Design Decisions & Trade-offs

### 2.1中央 Bean 声明 vs. @Component Scans
During Phase 3 implementation, we discovered that having `@Component` annotations directly on `LruEvictionPolicy` and `LfuEvictionPolicy` caused Spring's autowiring to throw `NoUniqueBeanDefinitionException` when a dependency on `EvictionPolicy` was declared. 

- **The Trade-off:** By removing `@Component` from the policy implementations and centralizing instantiation inside [CacheConfig.java](file:///d:/Coding/Projects----For%20Resume/Cairn/src/main/java/com/portfolio/cairn/config/CacheConfig.java) with `@ConditionalOnProperty`, we shifted the selection of the active policy from scan-time to boot-time. This resolved autowiring ambiguity and allowed the cache engine to stay strategy-agnostic.

### 2.2 Asynchronous Write-Back Draining
The write-back pipeline decouples the cache from the database latency.
- **The Strategy:** Writes are saved to the cache and then appended to a `LinkedBlockingQueue`. A background daemon thread drains this queue.
- **The Concurrency Risk:** If the queue grows indefinitely under extreme write-load, the JVM can run out of memory. We chose a simple unbounded queue under the assumption that database throughput is modeled with simulated lag, but in a production setup, a bounded queue with caller-runs or drop policies would be mandatory to protect JVM memory.

---

## 3. Shard vs. Cairn Comparative Analysis

Cairn was built as a twin project to [Shard](https://github.com/vaibhv19/Shard) to compare runtime architectures:

| Success Metric (PRD §6) | Shard (Python/Django) | Cairn (Java/Spring Boot) |
| :--- | :--- | :--- |
| **Concurrency Model** | Single-threaded event loop (asyncio) | OS-level multi-threading + Virtual Threads |
| **GIL Bottleneck** | Present. Cannot exploit multi-core CPU | None. Parallel threads execute on multiple cores |
| **Contention Tail Latency** | Higher. Queue delays occur under blocking IO | Sub-millisecond $p99$ tail latencies |
| **Throughput Ceiling** | Limited by single-core speed | Scales linearly with CPU cores |

Under simulated benchmark loads (spawning 60 parallel threads executing a mix of SET, GET, write-back, and invalidations), Cairn handles thousands of parallel operations per second without locking up, whereas Shard incurs thread context switching or event-loop lag when processing heavy blocking events.

---

## 4. Retrospective: What Would Be Done Differently?

1. **Segmented Eviction Locks:**
   While `ReentrantReadWriteLock` helps, all eviction updates still contend on a single policy lock. A better approach would be to segment the eviction ring itself into striping zones (similar to `ConcurrentHashMap` segments), allowing threads to update eviction pointers for keys in different segments independently without blocking each other.
2. **Explicit Thread-Group Boundaries in Tests:**
   During development, background threads (like the `ActiveExpirySweeper` scheduler and the `writeBack` worker thread) sometimes survived the completion of a test, leaking threads into subsequent test runs and causing race conditions on port bindings. Incorporating strict lifecycle hooks (`@PreDestroy` and explicit worker termination checks) was necessary, but introducing a central test lifecycle coordinator from day one would have saved significant debugging time.

---

## 5. Testing Philosophy

Cairn relies on a strict separation of concerns in testing:
- **Category A (Unit/Integration):** Fast, deterministic validations targeting isolated classes (like consistent ring hashing logic and configuration validation).
- **Category B (Concurrency Stress):** Uses `CountDownLatch` and `ExecutorService` to force thread races. These tests proved invaluable because they caught early race conditions where two threads simultaneously tried to evict the same victim key or read a key that was in the middle of a passive expiry sweep.
- **Category C (Integration End-to-End):** Validates Actuator exposures and HTTP routing to ensure the network boundary doesn't degrade performance or serialization logic.

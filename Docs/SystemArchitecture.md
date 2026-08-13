# System Architecture Document — Cairn (Distributed Cache Service)

## Document Control
* **Document Version:** 0.1.0
* **Status:** Draft
* **Authors:** Portfolio Owner / Technical Architect
* **Target Release:** v0.1.0 (Docs & MVP)
* **Twin Project Reference:** [Shard System Architecture]()

---

## 1. High-Level Component Diagram

The following diagram illustrates the flow of requests from the client down to the cache storage and background system tasks within a single node (MVP) and across a cluster (Phase 2).

```mermaid
graph TD
    Client[Client / Benchmark Runner]
    
    subgraph Cluster Layer (Phase 2)
        Routing[Routing / Proxy Layer]
        ConsistentHash[Consistent Hashing Ring]
    end

    subgraph "Cairn Node (JVM Instance)"
        REST[Spring REST Controller]
        CacheEngine[Cache Engine Core]
        Storage[(ConcurrentHashMap Segmented Store)]
        
        subgraph Eviction Engine
            EvictionStrategy["Eviction Strategy (LRU/LFU Bean)"]
            RWLock[ReentrantReadWriteLock]
        end
        
        subgraph Expiration Engine
            ActiveExpiry["Active Expiry Sweeper (Scheduled Threads)"]
        end

        subgraph Metrics Engine
            MetricsCollector[Micrometer Counter / LongAdder]
        end
    end

    Client -->|HTTP Request| Routing
    Routing -->|Lookup Ring| ConsistentHash
    Routing -->|Proxy Request| REST
    REST -->|Cache Commands| CacheEngine
    CacheEngine <-->|Read / Write Entry| Storage
    CacheEngine -->|Lock & Update Pointers| RWLock
    RWLock --> EvictionStrategy
    ActiveExpiry -->|Background Expiry Sweep| CacheEngine
    CacheEngine -->|Record Event| MetricsCollector
```

### Component Flow Description:
1. **Client / Benchmark Runner:** Sends HTTP requests containing cache commands (`SET`, `GET`, `DELETE`).
2. **Routing / Proxy Layer (Phase 2):** Hashes the request key and queries the **Consistent Hashing Ring** to locate the target node, proxying the request to its HTTP port.
3. **REST Controller:** Handles HTTP serialization/deserialization and routes requests to the Cache Engine Core.
4. **Cache Engine Core:** Coordinates key access, eviction checks, expiration calculations, and metrics reporting.
5. **ConcurrentHashMap Storage:** The underlying lock-free storage map representing the cache namespace.
6. **Eviction Engine:** Applies either LRU or LFU logic protected by a `ReentrantReadWriteLock` to identify and remove keys when memory capacity is reached.
7. **Expiration Engine:** Periodically sweeps the cache namespace via background scheduler threads to evict keys whose time-to-live (TTL) has passed.

---

## 2. Concurrency Architecture

The primary differentiator of Cairn is its safety under true multi-threaded parallel execution. Unlike Python, where thread execution is serialized, multiple JVM threads can execute Cairn cache logic at the same physical instance in time.

```
Request Thread (Read/Write)                Background Expiration Sweep Thread
      |                                                |
      v                                                v
Check Key Expiry (GET)                          Sample Keys sequentially
  - If Expired:                                 - If Expired:
    Acquire WRITE Lock                            Acquire WRITE Lock
    Delete key & update metadata                  Delete key & update metadata
    Release WRITE Lock                            Release WRITE Lock
  - If Active:                                  - If Valid:
    Acquire READ Lock                             Skip Key
    Update Eviction stats (LRU/LFU)               Release WRITE Lock (if held)
    Release READ Lock                                  |
      |                                                v
      +------------------ MUTEX / LOCKS ---------------+
```

### 2.1 Synchronization Boundaries
* **Index Access:** The primary `ConcurrentHashMap` handles thread safety for basic lookups. Individual hash buckets are locked for updates, but read queries remain entirely lock-free.
* **Eviction Metadata Synchronization:**
  * To implement $O(1)$ eviction, policies (like LRU) must maintain a linked list. Standard Java linked lists are not thread-safe.
  * A `ReentrantReadWriteLock` protects this metadata.
  * When a key is read (`GET`), the cache thread obtains a `ReadLock` to check the value and a promotion task updates the node's position in the LRU list.
  * When a write occurs (`SET`), if the capacity limit is breached, the thread obtains a `WriteLock` to isolate eviction pointers, identify the victim key, delete it from the `ConcurrentHashMap`, and remove it from the list.

### 2.2 Expiry Sweep & Request Thread Interaction
To prevent background operations from bottlenecking active user requests, expiration is executed via a two-tier mechanism:
1. **Passive Expiration (Synchronous):** When a user requests a key via `GET`, the request thread checks if `currentTime > expiryTime`. If expired, it triggers a delete, records a miss, and returns null.
2. **Active Expiration (Asynchronous):** A background thread pool (`ScheduledExecutorService`) spawns an expiry sweep task at a configured interval (e.g., every 5 seconds). To avoid blocking client requests, the background thread does not scan the entire database in one block; it performs a **probabilistic sample scan** (similar to Redis). It samples $N$ keys, evicts expired ones, and wraps up execution. It only locks the segments it alters, keeping contention extremely low.

---

## 3. Phase 2 Distributed Architecture

Consistent hashing allows the cache to scale horizontally across multiple static JVM processes.

```
       Consistent Hashing Ring (0 - 2^32)
                 Node A (Virtual v1)
                    /          \
                   /            \
  Node C (Virtual v2)          Node B (Virtual v1)
                  \              /
                   \            /
                 Node A (Virtual v2)
```

### 3.1 Consistent Hashing Ring
* **Representation:** Built using a Java `TreeMap<Long, Node>`, representing a ring from $0$ to $2^{32}-1$.
* **Virtual Nodes:** To prevent key concentration on a single node, each node configures $V$ virtual nodes (defaults to 150). Each virtual node is placed on the ring by hashing the string representation of its physical index (e.g., `Node-A#1`, `Node-A#2`).
* **Routing Algorithm:**
  1. Hash the requested cache key using a Murmur3 hash function to get a hash value $H$.
  2. Query the `TreeMap` using `tailMap(H)` to find the next virtual node whose hash is greater than or equal to $H$.
  3. If the tail map is empty, wrap around to the first entry in the `TreeMap`.
  4. Forward the HTTP command to the physical node owning that virtual node.

### 3.2 Node Transition & Rebalancing Behavior
Since membership is **static** (read from configuration at boot), node additions and removals are calculated offline:
* When a node is added/removed from the configuration file and the proxy is updated, the routing ring recalculates.
* Because consistent hashing is used, the ring ensures that only $K/N$ keys migrate to new nodes (where $K$ is total keys and $N$ is total nodes), preventing a cascading miss storm across the entire cache cluster.

---

## 4. Phase 3 Architecture: Metrics & Invalidation

### 4.1 Invalidation Hook
Cache invalidations (individual, wildcard, or cluster-wide flushes) trigger write updates across the index and eviction pointers. Wildcard invalidations (e.g., prefix match `user:*`) perform segment-based scans inside the `ConcurrentHashMap` to locate and purge targets.

### 4.2 Non-Blocking Metrics Aggregation
Recording metrics like latency percentiles and hit rates can easily degrade application performance if not designed correctly:
* **Metrics Storage:** Cairn uses **Micrometer** backed by non-blocking statistics structures.
* **Lock-Free Counters:** Instead of using synchronized blocks to count hits/misses, Cairn leverages `java.util.concurrent.atomic.LongAdder`. This structure splits counters into cell arrays per thread, avoiding memory bus contention and keeping metrics overhead negligible.
* **Latency Histograms:** Latency percentiles ($p50, p99$) utilize decaying ring-buffer reservoirs to capture latency distributions without locking the request paths.

---

## 5. Architectural Differences vs. Shard (The Concurrency Diff)

Cairn's system architecture mirrors Shard's logical design, but departs fundamentally in its concurrency synchronization.

| Architectural Component | Shard (Django / Python Twin) | Cairn (Spring Boot / Java Twin) |
| :--- | :--- | :--- |
| **Execution Model** | Single-threaded process execution loop. Concurrency is simulated by context-switching threads or workers under the Python GIL. | Multi-threaded native thread execution. Threads run in parallel across physical CPU cores. |
| **Store Synchronization** | Does not require thread synchronization for dictionary reads/writes because the GIL prevents race conditions during dictionary mutation. | Requires explicit concurrent structures (`ConcurrentHashMap`) and locks to prevent JVM memory visibility issues and segment corruption. |
| **Eviction Pointer Mutations** | LRU/LFU lists are mutated directly without locks, as thread context switches are controlled at the interpreter level. | Eviction pointer changes must acquire fine-grained locks (`ReentrantReadWriteLock`) to block concurrent threads from corrupting pointers. |
| **Active Expiration Sweeper** | Typically runs as a single cooperative task or greenlet, pausing the thread loop momentarily to prune keys. | Runs as a separate OS thread in a daemon thread pool, executing concurrent deletes in parallel with user request handling. |

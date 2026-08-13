# Product Requirements Document (PRD) — Cairn (Distributed Cache Service)

## Document Control
* **Document Version:** 0.1.0
* **Status:** Draft
* **Authors:** Portfolio Owner / Technical Architect
* **Milestone Reference:** v0.1.0 (Docs Complete)
* **Twin Project Reference:** [Shard (Django/Python Cache Engine)]()

---

## 1. Overview & Problem Statement

### 1.1 What is Cairn?
**Cairn** is a high-performance, distributed, thread-safe, in-memory cache service (akin to a mini-Redis) built on the Java and Spring Boot ecosystem. It is designed to act as a fast key-value store supporting configurable eviction policies, Time-To-Live (TTL) expiration, static distributed sharding via a consistent hashing ring, and comprehensive operational observability.

### 1.2 The Concurrency Duel: Cairn vs. Shard
Cairn is engineered as the direct functional twin to **Shard**, an identical cache engine implemented in Django/Python. 

While Shard and Cairn share the exact same features, APIs, and architectural boundaries, their implementation languages represent two fundamentally different approaches to concurrency:
* **Shard (Python/Django):** Bound by Python’s Global Interpreter Lock (GIL). Multi-threading in Shard is essentially time-sliced/interleaved execution on a single CPU core. Concurrency limits are bound by interpreter locks rather than hardware constraints.
* **Cairn (Java/Spring Boot):** Utilizes the JVM’s native, true multi-threaded parallel execution model. Threads run simultaneously across multiple physical CPU cores. This necessitates explicit concurrency control, lock striping, atomic memory operations, and careful consideration of memory visibility and CPU cache lines.

```mermaid
graph TD
    subgraph Client Application / Benchmark Suite
        Client[Concurrent Clients / Load Generator]
    end

    subgraph Shard (Python Twin)
        GIL[Global Interpreter Lock - GIL]
        PyThreads[Interleaved OS Threads]
        PyCache[Single-Threaded Engine Execution]
        Client -.->|Interleaved Requests| GIL
        GIL --> PyThreads
        PyThreads --> PyCache
    end

    subgraph Cairn (Java Twin - This Project)
        JVM[JVM Thread Scheduler]
        JavaThreads[True Parallel OS Threads]
        LockStriping[Lock-Striped / Lock-Free Map]
        Client --->|Parallel Requests| JVM
        JVM --> JavaThreads
        JavaThreads --> LockStriping
    end
```

By maintaining identical functional requirements, Shard and Cairn serve as a direct comparative study of concurrent throughput, latency distributions under load, CPU utilization efficiency, and the engineering complexity required to guarantee thread safety in true parallel environments versus GIL-isolated runtimes.

---

## 2. Goals & Non-Goals

To maintain a realistic scope for an engineering portfolio project, the system boundaries are strictly defined.

### 2.1 Goals
* **True Parallel Thread Safety:** Implement an in-memory cache engine capable of handling high-contention parallel writes, reads, evictions, and expiration sweeps without data corruption or lock starvation.
* **Pluggable Eviction Strategy:** Establish an eviction framework utilizing the Strategy Pattern, allowing cache instances to switch between Least Recently Used (LRU) and Least Frequently Used (LFU) eviction algorithms at startup via Spring Beans.
* **Dual Expiration Mechanics:** Guarantee that expired keys are never served (passive eviction) and that memory is reclaimed predictably via background sweeps (active eviction).
* **Static Horizontal Scaling:** Implement consistent hashing to shard keys across multiple nodes, presenting a unified logical cache ring to the client.
* **Deep Observability:** Capture microsecond-level latency percentiles, hit/miss ratios, and memory stats to facilitate direct benchmarking against Shard.

### 2.2 Non-Goals
* **Redis Protocol (RESP) Compatibility:** Cairn will expose its own REST API via Spring Boot Controllers, not a TCP-level RESP parser.
* **Persistent Storage (No AOF/RDB):** Cairn is strictly in-memory. Persistence features (like Redis's Append-Only File or Redis Database backups) are out of scope.
* **Dynamic Cluster Membership:** There is no Gossip protocol, automatic peer discovery, or cluster consensus (e.g., Raft). Node membership in the hashing ring is statically defined via Spring Configuration.
* **High Availability & Replication:** Active node replication, master-slave configurations, and automated failover are out of scope.

---

## 3. Target Users & Use Cases

This project is a high-caliber technical demonstration piece. Its target audience consists of:
* **Technical Recruiters & Engineering Managers:** Evaluating clean architectural patterns, robust code layout, testing practices, and documentation quality.
* **Systems Engineers & Technical Reviewers:** Evaluating JVM-specific concurrency mastery, lock-striping implementation, thread pool tuning, and distributed systems routing design.
* **Benchmark Comparison:** Developers analyzing the performance trade-offs of the Python GIL vs. the Java Multi-Threaded Model.

---

## 4. Functional Requirements

The implementation is structured in three progressive phases to ensure a clean, testable evolution of the codebase.

```
+--------------------------------------------------------+
| MVP: Single-Node Engine & Core Cache API               |
| - Key-Value Core, Thread-Safe Map, LRU/LFU Eviction    |
| - Dual Expiration (Active/Passive), REST API Controller |
+---------------------------+----------------------------+
                            |
                            v
+--------------------------------------------------------+
| Phase 2: Static Sharding & Distribution                 |
| - Consistent Hashing Ring (Virtual Nodes)              |
| - Routing/Proxy Layer, Static Cluster Configuration    |
+---------------------------+----------------------------+
                            |
                            v
+--------------------------------------------------------+
| Phase 3: Advanced Invalidation & Observability          |
| - Invalidation APIs, Write-Through/Back Mechanics      |
| - Actuator Metrics, Grafana/Actuator Dashboard          |
+--------------------------------------------------------+
```

### 4.1 MVP: Single-Node Engine & Core API
* **FR-1.1: Core Cache Access:** The system must support basic CRUD operations on keys and values (strings):
  * `SET(key, value)`: Creates or updates a cache entry.
  * `GET(key)`: Returns the value or `404 Not Found`/`null`.
  * `DELETE(key)`: Removes the key from the cache.
  * `EXISTS(key)`: Checks presence without affecting LRU/LFU access order.
* **FR-1.2: Swappable Eviction Strategy:** Cache instances must accept a configuration parameter specifying the eviction policy:
  * **LRU (Least Recently Used):** Discards the least recently accessed items first.
  * **LFU (Least Frequently Used):** Discards items with the lowest access frequency count first.
  * *Constraint:* Eviction must execute in $O(1)$ time complexity to prevent system slowdown as the cache size approaches capacity.
* **FR-1.3: Key Expiration (TTL):** Expiration can be specified at write time via `SET(key, value, ttl_seconds)` or via an explicit `EXPIRE(key, ttl_seconds)` command.
* **FR-1.4: Dual Expiry Mechanism:**
  * **Passive Expiry:** During a `GET` or `EXISTS` request, if the key's TTL has elapsed, the cache must immediately evict the key and return null/404, preventing stale data retrieval.
  * **Active Expiry:** A background thread managed via `ScheduledExecutorService` must periodically sample keys and purge expired ones to free memory.
* **FR-1.5: REST API Boundary:** All core operations must be exposed via standard HTTP methods:
  * `POST /api/v1/cache` (body: `{ "key": "...", "value": "...", "ttl": 60 }`)
  * `GET /api/v1/cache/{key}`
  * `DELETE /api/v1/cache/{key}`
  * `POST /api/v1/cache/{key}/expire` (body: `{ "ttl": 30 }`)
  * `GET /api/v1/cache/{key}/ttl` (returns remaining TTL seconds)

### 4.2 Phase 2: Static Sharding & Distribution
* **FR-2.1: Consistent Hashing Ring:** Cairn must implement a client-side consistent hashing ring. Keys must map deterministically to specific nodes. Virtual nodes must be supported to ensure uniform key distribution across the ring.
* **FR-2.2: Static Routing proxy:** A routing component must accept operations, hash the key, locate the correct target node on the ring, and proxy the request to that node.
* **FR-2.3: Config-Driven Node Membership:** The set of available cache nodes (host IP and port) must be loaded statically from Spring application properties (e.g., `application.yml`).
* **FR-2.4: Deterministic Node Transition (Rebalancing):** Although the cluster is static, the system must support manual configuration updates (e.g., adding/removing a node in config and restarting/refreshing). The system must document and verify what percentage of keys migrate on node transition, verifying consistent hashing behavior ($K/N$ key movement, where $K$ is total keys and $N$ is number of nodes).

### 4.3 Phase 3: Cache Invalidation & Observability
* **FR-3.1: Explicit Invalidation API:** Support selective key invalidation, wildcard pattern invalidation (e.g., prefix-based purging like `user:*`), and cluster-wide flush.
* **FR-3.2: Write Semantics Simulation:** Implement testable interfaces for:
  * **Write-Through:** Write updates cache and simulated backing database synchronously.
  * **Write-Back (Write-Behind):** Write updates cache instantly, and asynchronously queues database updates.
* **FR-3.3: Metrics Collection (Spring Boot Actuator):** Expose operational metrics via `/actuator/prometheus` or JSON endpoints:
  * Hit/Miss Ratio (tracked globally and per-node).
  * Total Evictions (split by policy vs TTL expiration).
  * Active key count and estimated heap consumption.
  * Operation Latency (split into $p50$, $p90$, $p95$, and $p99$ percentiles).
* **FR-3.4: Visual Dashboard:** Provide a lightweight UI dashboard (or Prometheus/Grafana export config) summarizing metrics across nodes, allowing developers to visually compare LRU vs. LFU hit rates under different load patterns.

---

## 5. Non-Functional Requirements (NFRs)

These architectural requirements guarantee the engineering rigors of the project and frame the comparison tests with Shard.

### 5.1 Concurrency & Data Correctness (The Primary NFR)
* **Thread-Safety Invariance:** No concurrent operation (including background TTL sweep threads, HTTP handlers, and eviction sweeps) may corrupt internal pointers, cause memory leaks, or result in out-of-order operations.
* **Lock-Striping / Fine-Grained Locking:** Avoid global locks (e.g., synchronizing the entire cache map). Use ConcurrentHashMap or split locks (lock striping) so that concurrent reads/writes on separate keys do not block each other.
* **Eviction Safety:** Evicting a key under LFU/LRU demands metadata updates (e.g., moving nodes in a doubly-linked list or updating frequency buckets). These structures must be updated thread-safely without introducing deadlocks or race conditions.

### 5.2 Performance & Latency
* **Sub-Millisecond Engine Latency:** Excluding HTTP network overhead, the cache engine must process read and write actions in sub-millisecond ranges ($< 1$ ms at $p99$ under zero contention).
* **Throughput Scaling:** The service must scale throughput linearly with the number of CPU cores when multiple concurrent clients execute requests.

### 5.3 Memory Constraints
* **Memory Limits:** The cache size must be bounded by a maximum key capacity configured at startup (e.g., `cairn.cache.max-size=10000`).
* **Immediate Reclamation:** As soon as the cache size exceeds `max-size`, the configured eviction policy must execute synchronously with the write operation to immediately free memory space.

### 5.4 Operational & System Constraints
* **Framework:** Java 21 (LTS) and Spring Boot 3.3.x.
* **No Database Dependencies:** The MVP must run out of the box with zero external database dependencies. Any simulated backing database for write-through/back tests must be mockable in-memory.

---

## 6. Success Metrics

A phase is considered complete when it satisfies the following target metrics:

| Phase | Metric | Target | Verification Method |
| :--- | :--- | :--- | :--- |
| **MVP** | Concurrency Correctness | 0% Data Corruption / Null Pointer Exceptions under parallel thread load. | Run concurrency test suite (100 parallel threads updating same/different keys). |
| **MVP** | Eviction Accuracy | Exactly $N$ oldest (LRU) or least frequent (LFU) keys are evicted when cache capacity is breached. | Automated verification scripts checking cache dump matches expected list. |
| **Phase 2**| Hashing Uniformity | Node key allocation variance must be $< 15\%$ across all static nodes. | Run client benchmark inserting 100,000 keys; inspect distribution per node. |
| **Phase 2**| Rebalance Minimalist Movement | Adding a node to a ring of $N$ nodes must migrate no more than approximately $1/(N+1)$ of keys. | Test script counting key transfers during node addition on the ring. |
| **Phase 3**| Latency Benchmarking | $p99$ Cache API Response time $< 5$ ms (over local network) under a write load of 1,000 requests/sec. | JMeter or `wrk` load generation script. |
| **Phase 3**| Comparative Advantage | Cairn throughput must exceed Shard throughput by $\ge 3\times$ when client threads exceed CPU core count. | Concurrent benchmark suite comparison report. |

---

## 7. Assumptions & Constraints

* **Network Overhead:** While the cache engine itself runs in microseconds, REST API exposure introduces HTTP serialization and network overhead. Benchmarks comparing raw engine speed should bypass HTTP (in-memory unit tests), while REST benchmarks should be run on a loopback interface to minimize network jitter.
* **Single-JVM Focus for MVP:** The MVP assumes all cache instances run on a single machine or local loopback instances during development to simplify testing.
* **Static Topology:** We assume that node additions/removals are infrequent operations controlled by DevOps/deployment configurations, not automatic self-healing operations.

---

## 8. Open Questions

> [!IMPORTANT]
> The following architectural decisions require final alignment before starting Phase 1 implementation.

1. **How should we represent capacity in eviction?**
   * *Option A (Recommended):* Key-Count Limit (e.g., maximum of 5,000 entries). This is deterministic and easy to verify.
   * *Option B:* Memory-Limit (e.g., maximum of 128MB heap usage). While closer to production caches like Redis, measuring JVM object sizes accurately is notoriously complex and can introduce performance overhead.
   * *Decision:* Proceed with **Option A** for MVP, and evaluate **Option B** as a Phase 3 extension.

2. **Should consistent hashing map keys directly to nodes, or should we use Virtual Nodes?**
   * *Recommendation:* Use Virtual Nodes (e.g., 100-200 virtual tokens per physical node) to prevent "hot spots" where one node receives a disproportionate share of traffic due to poor hash distribution.

3. **Should the eviction strategies be thread-safe on their own or rely on the parent cache engine's locks?**
   * *Recommendation:* The eviction policies (LRU/LFU double-linked list modifications) should be protected by the parent cache's locks or utilize concurrent data structures to prevent lock nesting, which could lead to deadlocks.

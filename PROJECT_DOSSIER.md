# Cairn

## 1. Project Overview

Cairn is a distributed, thread-safe, in-memory key-value cache service built with Java 21 and Spring Boot 3.3.2. It implements core caching primitives with pluggable eviction policies (LRU and LFU), dual-tier TTL expiration (synchronous passive checks and adaptive background sweeps), static cluster sharding with virtual-node consistent hashing, proxy routing, asynchronous write semantics (write-through and write-back), and Micrometer/Actuator metrics instrumentation for Prometheus.

---

## 2. Why I Built It

Cairn was engineered as the direct functional twin to **Shard**, an identical cache engine built in Python/Django. The purpose was to explore the fundamental differences between two concurrency models under identical workload constraints:

1. **Python / Django (Shard):** Concurrency constrained by the Global Interpreter Lock (GIL) and single-threaded async event loops.
2. **Java 21 / Spring Boot (Cairn):** True parallel multi-core execution utilizing native JVM multi-threading, Virtual Threads (Project Loom), explicit lock-striping, non-blocking atomics (`LongAdder`), and fine-grained mutex coordination.

Building both systems against the same functional requirements provided concrete engineering insight into race conditions, memory visibility, lock contention, and the trade-offs between GIL isolation and parallel hardware utilization.

---

## 3. Problem / Question

- **How do you build a predictable, thread-safe in-memory cache engine that prevents data corruption across high-contention parallel reads, writes, and evictions without degenerating into a single-threaded bottleneck?**
- **How do you coordinate pluggable eviction strategies (such as doubly-linked lists and frequency buckets) when lock-free maps like `ConcurrentHashMap` do not track order or frequency across mutating entries?**
- **How do you deterministically distribute keys across a cluster of standalone nodes while minimizing key migration during topology alterations?**
- **How do you avoid virtual thread carrier pinning in Java 21 when coordinating synchronizers under high contention?**

---

## 4. What It Actually Does

1. **Key-Value Operations via REST:** Exposes HTTP endpoints for `SET` (with optional TTL), `GET`, `DELETE`, `EXISTS`, `EXPIRE`, and `TTL` operations under `/api/v1/cache`.
2. **Pluggable Eviction:** Enforces a configurable key-count capacity limit (`cairn.cache.max-size`), dynamically swapping between:
   - **LRU (Least Recently Used):** Tracks access recency via a custom doubly-linked list and node map.
   - **LFU (Least Frequently Used):** Tracks frequency via a frequency-to-keys `LinkedHashSet` table and an $O(1)$ minimum frequency pointer.
3. **Dual Expiration Mechanics:**
   - **Passive Expiration:** Evaluates TTL synchronously on `GET` and `EXISTS` calls. Expired keys are pruned and counted as misses.
   - **Active Adaptive Sweeper:** Runs a background daemon on a `ScheduledExecutorService` that samples batches of keys (20 keys per batch). If >25% of the sample is expired, it loops adaptively to sweep again immediately.
4. **Consistent Hashing & Proxy Routing:**
   - Hhashes keys onto a 32-bit integer ring using Murmur3-32 with 150 virtual nodes per physical node configured in `application.yml`.
   - The proxy layer (`NodeRouter`) inspects the ring: local keys execute directly against the engine; remote keys are proxied over HTTP via pooled Netty `WebClient` instances.
5. **Cluster Invalidation & Write Semantics:**
   - Supports exact key, wildcard prefix (`prefix:*`), and full flush (`*`) invalidations, automatically broadcasting wildcard purges across all cluster nodes.
   - Implements synchronous **write-through** to a simulated database (`MockDatabase`) and asynchronous **write-back** queued via `LinkedBlockingQueue` and processed by a dedicated worker thread.
6. **Telemetry & Observability:**
   - Tracks cache hits, misses, policy evictions, and TTL evictions with lock-free `LongAdder` counters.
   - Captures latency distributions ($p50$, $p95$, $p99$) using Micrometer timers, exposed to Prometheus via Spring Boot Actuator (`cairn.cache` metrics).
   - Provides a pre-configured Grafana panel configuration (`Docs/grafana-dashboard.json`).

---

## 5. Architecture

```
                                      +---------------------------------------------+
                                      |          Client / Load Generator            |
                                      +---------------------------------------------+
                                                             |
                                                             | HTTP Request
                                                             v
+-------------------------------------------------------------------------------------------------------------------------+
| Cairn Node (Spring Boot 3.3.2 / Java 21)                                                                                |
|                                                                                                                         |
|   +------------------------------------+          +-----------------------------------------------------------------+   |
|   |          NodeRouter                | -------- | ConsistentHashRing (TreeMap + Murmur3-32 / 150 Virtual Nodes)   |   |
|   |  (Local Dispatch / WebClient Proxy)|          +-----------------------------------------------------------------+   |
|   +------------------------------------+                                                                                |
|         |                                      |                                                                        |
|         | (Local node match)                   | (Remote node match)                                                    |
|         v                                      v                                                                        |
|   +------------------------------------+  [ HTTP Proxy Forward -> Remote Cairn Node ]                                   |
|   |          CacheEngine               |                                                                                |
|   |  +------------------------------+  |                                                                                |
|   |  | ConcurrentHashMap Storage    |  |                                                                                |
|   |  +------------------------------+  |                                                                                |
|   |  | ReentrantLock (writeLock)    |  |                                                                                |
|   |  +------------------------------+  |                                                                                |
|   |  | Active Expiry Sweeper Daemon |  |                                                                                |
|   |  +------------------------------+  |                                                                                |
|   |  | Write-Back Queue & Worker    |  |                                                                                |
|   |  +------------------------------+  |                                                                                |
|   +------------------------------------+                                                                                |
|         |                                      |                                      |                                 |
|         v                                      v                                      v                                 |
|   +--------------------------+           +--------------------------+           +--------------------------+            |
|   | EvictionPolicy Strategy  |           | InvalidationService      |           | Metrics & Observability  |            |
|   | (LruPolicy / LfuPolicy)  |           | (Wildcard & Key Purges)  |           | (LongAdder / Micrometer) |            |
|   +--------------------------+           +--------------------------+           +--------------------------+            |
+-------------------------------------------------------------------------------------------------------------------------+
```

### Component Breakdown

| Layer / Component | Implementation Class | Responsibility |
| :--- | :--- | :--- |
| **Storage Engine** | `CacheEngine`, `CacheEntry` | Coordinates key-value storage, lock boundaries, and write delegation. |
| **Eviction Engine** | `EvictionPolicy`, `LruEvictionPolicy`, `LfuEvictionPolicy` | Maintains policy-specific recency lists or frequency buckets protected by internal `ReentrantLock` synchronizers. |
| **Key Expiration** | `ActiveExpirySweeper` | Schedules periodic background sweeps with probabilistic sampling loops. |
| **Sharding & Proxy** | `ConsistentHashRing`, `NodeConfig`, `NodeRouter` | Resolves target node allocations on the consistent ring and proxies remote requests over HTTP. |
| **Invalidation** | `InvalidationService` | Evaluates single key, prefix wildcard, or full flush deletions across local or cluster nodes. |
| **Metrics Binding** | `CacheMetricsCollector`, `CairnMeterBinder` | Binds lock-free counters and latency percentile timers to Actuator and Prometheus. |

---

## 6. Important Technical Decisions

### 1. `ReentrantLock` Over `synchronized` Blocks
- **Context:** Virtual Threads (Project Loom) in Java 21 can experience carrier-thread pinning if a virtual thread blocks inside a `synchronized` block that executes I/O or contended monitors.
- **Decision:** Replaced all internal monitor synchronizers with explicit `java.util.concurrent.locks.ReentrantLock` instances across `CacheEngine`, `LruEvictionPolicy`, and `LfuEvictionPolicy`.
- **Trade-off:** Requires explicit `try-finally` unlock boilerplate, but guarantees non-pinning virtual thread scheduling.

### 2. Boot-Time Strategy Wiring via `@ConditionalOnProperty`
- **Context:** Declaring multiple `@Component` implementations of `EvictionPolicy` causes autowiring ambiguity (`NoUniqueBeanDefinitionException`).
- **Decision:** Centralized bean definitions inside `CacheConfig.java` using Spring's `@ConditionalOnProperty(name = "cairn.cache.eviction-policy", havingValue = "...")`.
- **Trade-off:** Eviction policy is fixed at node boot time rather than changed dynamically at runtime per request, which eliminates strategy lookup overhead.

### 3. Double-Checked Locking on Passive Expiry
- **Context:** Running full write locks on every `get()` invocation would degrade read throughput to single-threaded performance.
- **Decision:** `CacheEngine.get()` first reads the `CacheEntry` without locking. Only if `System.currentTimeMillis() > entry.expiryTime()` does the thread acquire `writeLock`, re-verify the expiration condition under the lock, and delete the stale entry.
- **Trade-off:** Requires reading the entry twice on an expired hit, but ensures fast path reads remain lock-free.

### 4. Non-Blocking Telemetry with `LongAdder`
- **Context:** Under high concurrency across 50+ threads, updating `AtomicLong` counters causes CPU cache-line bouncing due to repeated Compare-And-Swap (CAS) retries.
- **Decision:** Used `java.util.concurrent.atomic.LongAdder` for hits, misses, policy evictions, and TTL evictions.
- **Trade-off:** Slightly higher memory footprint due to internal cell striping, but eliminates CAS contention on hot metric paths.

---

## 7. Interesting Engineering Problems

### 1. The Eviction / Storage Split Contention Race
- **Problem:** `ConcurrentHashMap` handles thread-safe key insertions, but does not know about LRU list pointers or LFU frequency buckets. If thread A inserts a key and triggers eviction while thread B is concurrently deleting or reading that same victim key, pointers in the doubly-linked list could become corrupted or point to null.
- **Solution:** Enforced a lock hierarchy where `CacheEngine.writeLock` coordinates key-capacity checks, victim eviction selection, and map mutation within an atomic critical section, ensuring the eviction policy data structure and the backing map remain synchronized.

### 2. Consistent Hashing Virtual Node Distribution
- **Problem:** Standard hash modulo (`hash(key) % N`) causes massive cache thrashing when a node is added or removed (rebalancing almost all keys). A naive consistent hash ring with only 1 virtual node per physical node leads to uneven load distribution (hotspots).
- **Solution:** Implemented `ConsistentHashRing` using Murmur3-32 with 150 virtual nodes per physical node mapped onto a `TreeMap<Long, String>`. Tests confirm a coefficient of variation < 4% across 100,000 keys and a key migration rate of ~22.8% on expanding from 3 to 4 nodes (close to the theoretical optimal $1/(N+1) = 25\%$).

---

## 8. Failure Modes / Things That Went Wrong

1. **Virtual Thread Carrier Pinning with `synchronized`:** Early implementations using Java `synchronized` keywords on eviction list operations caused virtual threads to pin their underlying OS carrier threads during heavy contention. Refactoring to `ReentrantLock` resolved this issue.
2. **Spring Autowiring Conflicts:** Initial implementation placed `@Component` directly on both `LruEvictionPolicy` and `LfuEvictionPolicy`, resulting in startup failures due to ambiguous beans. Resolved by introducing `CacheConfig` with `@ConditionalOnProperty`.
3. **Background Thread Leakage in Unit Tests:** Test suites running multi-node setups or active expiry sweeps occasionally leaked background executor threads across test boundaries. Adding `@PreDestroy` lifecycle termination hooks and deterministic test lifecycle teardown resolved test interference.

---

## 9. Verification / Testing

The test suite is divided into single-threaded unit tests and multi-threaded concurrency stress tests:

```
src/test/java/com/portfolio/cairn/
├── unit/
│   ├── CacheControllerTest.java            # MockMvc REST API boundary and validation tests (18 tests)
│   ├── CacheEngineTest.java                # Single-node CRUD and helper method tests (5 tests)
│   ├── CacheEvictionIntegrationTest.java   # Boundary capacity trigger tests (2 tests)
│   ├── CacheExpiryTest.java                # Passive and active sweep TTL tests (2 tests)
│   ├── CacheInvalidationTest.java          # Wildcard, exact, and flush invalidation tests (4 tests)
│   ├── CacheMetricsActuatorTest.java       # Actuator endpoint integration tests (2 tests)
│   ├── CacheMetricsCollectorTest.java      # LongAdder and latency timer assertions (2 tests)
│   ├── ConsistentHashRingTest.java         # Ring determinism, uniformity, and migration tests (5 tests)
│   ├── LruEvictionTest.java                # Doubly-linked list order & integrity checks (3 tests)
│   ├── LfuEvictionTest.java                # Frequency bucket movement & min-freq checks (4 tests)
│   ├── NodeConfigTest.java                 # Static YAML configuration parsing tests (6 tests)
│   ├── NodeRouterTest.java                 # Proxy forwarding and local routing tests (3 tests)
│   ├── RoutingControllerTest.java          # Cluster health and ring status endpoint tests (2 tests)
│   └── WriteSemanticsTest.java             # Write-through and write-back queue drainage tests (4 tests)
└── concurrency/
    ├── ConcurrentEvictionIntegrationTest.java # Parallel writes exceeding capacity under load (1 test)
    ├── ConcurrentExpiryTest.java              # Parallel read/write races during background sweeps (1 test)
    ├── EvictionRaceConditionTest.java         # High-contention eviction victim pointer integrity (2 tests)
    └── FullSystemConcurrencyTest.java         # 50+ concurrent threads executing mixed CRUD under load (2 tests)
```

- **Total Tests:** 68 passed (0 failures, 0 errors, 0 skipped).
- **Execution Time:** ~35 seconds on local JVM.

---

## 10. Deployment

- **Packaging:** Standard Spring Boot fat executable JAR via `spring-boot-maven-plugin`.
- **Multi-Node Local Cluster Script:** `run-cluster.bat` builds and launches a 3-node cluster locally:
  - Node-A on Port 8081 (`--cairn.cluster.local-node-id=Node-A`)
  - Node-B on Port 8082 (`--cairn.cluster.local-node-id=Node-B`)
  - Node-C on Port 8083 (`--cairn.cluster.local-node-id=Node-C`)
- **Metrics Scraping:** Exposes standard Prometheus scrape target at `/actuator/prometheus`.
- **Public Live URL:** None (local backend system service designed as an architectural comparative study).

---

## 11. What I Learned

1. **Lock Contention in High-Throughput Java:** Lock-free data structures like `ConcurrentHashMap` provide fast key lookup, but coordinating composite state (e.g., secondary order pointers) requires careful synchronization boundaries to avoid killing parallel performance.
2. **Virtual Thread Mechanics:** Virtual Threads are not a universal fix for CPU-bound or heavily locked critical sections; understanding monitor mechanics and avoiding carrier pinning is essential in Java 21+.
3. **Consistent Hashing Math:** Simulating ring distributions demonstrated that 150 virtual nodes per physical node provides an effective balance between memory overhead in the `TreeMap` and uniform key distribution.

---

## 12. What Changed in My Thinking

- **Before:** Assumed adding `synchronized` or coarse locks around cache methods was acceptable for ensuring thread safety in an MVP.
- **After:** Realized that under high-concurrency stress testing, coarse locks degrade multi-core JVM performance down to single-threaded speed. Using double-checked locking, fine-grained `ReentrantLock` boundaries, and `LongAdder` counters is necessary for predictable tail latency.

---

## 13. Distinctive / Interesting Details

- **Probabilistic Adaptive Sweeper:** Instead of naively scanning all keys in memory (which would cause $O(N)$ latency spikes), the active expiry sweeper samples small 20-key batches and dynamically adapts its iteration frequency based on the expired key ratio (>25%).
- **Dual-Model Companion Study:** Engineered specifically to be compared side-by-side against `Shard` (Python/Django), highlighting language-level architectural trade-offs between Python's GIL model and Java's parallel memory model.

---

## 14. Skills Demonstrated

### Engineering Skills
- Multi-threaded systems programming and concurrency control
- Distributed hashing and proxy routing implementation
- Data structure design (doubly-linked lists, frequency buckets, consistent hash rings)
- Automated stress and race-condition testing using Java concurrency utilities
- Non-blocking metrics instrumentation and telemetry

### Technologies & Tools
- Java 21 (Records, Virtual Threads)
- Spring Boot 3.3.2 (WebMVC, Actuator, WebFlux/WebClient, Validation)
- `java.util.concurrent` (`ConcurrentHashMap`, `ReentrantLock`, `ReentrantReadWriteLock`, `LongAdder`, `ScheduledExecutorService`, `LinkedBlockingQueue`)
- Guava (Murmur3-32 hashing)
- Micrometer & Prometheus
- Maven, JUnit 5, AssertJ, Mockito

### Concepts
- Consistent Hashing with Virtual Nodes
- Least Recently Used (LRU) & Least Frequently Used (LFU) Eviction
- Active Probabilistic & Passive TTL Expiry
- Write-Through & Write-Back Cache Semantics
- Lock-Striping, Memory Visibility, and Double-Checked Locking

### Best Skills for LinkedIn
1. Java 21
2. Spring Boot
3. Multi-Threading & Concurrency
4. Distributed Systems
5. Consistent Hashing
6. Performance Telemetry & Metrics (Micrometer / Prometheus)
7. Software Architecture

---

## 15. Public Content

### LinkedIn Project Description

I built **Cairn**, a distributed in-memory cache service in Java 21 and Spring Boot, as a concurrency and architecture study alongside its functional twin, **Shard** (built in Python/Django).

The goal was to examine how identical caching requirements behave when implemented under two fundamentally different execution models: Python's GIL-bound event loop versus Java's native parallel OS threads and Virtual Threads (Project Loom).

Key engineering aspects of Cairn include:
- A thread-safe storage core using `ConcurrentHashMap` and fine-grained `ReentrantLock` boundaries to avoid virtual thread carrier pinning.
- Pluggable LRU and LFU eviction strategies maintaining $O(1)$ doubly-linked list pointers and frequency bucket indexes.
- Dual-tier expiration: synchronous passive checks with double-checked locking and a background adaptive probabilistic sweeper.
- Distributed cluster sharding using a Murmur3-32 consistent hash ring (150 virtual nodes/node) paired with a pooled Netty `WebClient` proxy router.
- Non-blocking telemetry tracking hit ratios, evictions, and latency percentiles via `LongAdder` and Micrometer for Prometheus scraping.

Building Cairn highlighted the complexities of managing memory visibility, lock granularity, and data integrity under high contention without turning the cache into a single-threaded bottleneck.

### LinkedIn Featured Description
*(Omitted: Cairn is a local backend systems service without a public live web deployment URL.)*

### Resume Bullets

1. **Engineered a distributed in-memory cache service in Java 21 and Spring Boot**, implementing Murmur3-32 consistent hashing with 150 virtual nodes per node and a Netty-pooled proxy routing layer to enable deterministic key distribution across cluster instances.
2. **Designed pluggable LRU and LFU eviction algorithms and dual-tier TTL expiration**, utilizing custom doubly-linked lists, frequency-bucket indexes, fine-grained `ReentrantLock` boundaries to prevent virtual thread carrier pinning, and an adaptive probabilistic background sweeper.
3. **Implemented non-blocking telemetry and write pipelines**, utilizing `LongAdder` counters and Micrometer percentile timers for Prometheus metrics, write-through persistence, and asynchronous worker-drained write-back queues backed by 68 automated unit and concurrency stress tests.

## 16. GitHub Repository Metadata

### Repository Short Description
Distributed in-memory cache with consistent hashing, pluggable LRU/LFU eviction, and proxy routing.

### Suggested GitHub Topics
- `java-21`
- `spring-boot`
- `distributed-cache`
- `consistent-hashing`
- `concurrency`
- `virtual-threads`
- `lru-cache`
- `lfu-cache`
- `micrometer`
- `prometheus`

---

## 17. Claims That Should NOT Be Made

- Do **NOT** claim production scale, millions of active users, or live cloud deployments (Cairn is a local portfolio architecture study).
- Do **NOT** claim specific throughput figures (e.g., "100,000 requests/second") unless measured via a formal JMH benchmark suite.
- Do **NOT** claim persistent disk storage, database replication, or consensus protocols like Raft/Paxos (Cairn's cluster membership is static and in-memory).
- Do **NOT** claim a custom frontend UI was built (telemetry is visualized via Prometheus and Grafana).

---

## 18. Evidence / Source References

| Fact / Feature | Repository Source File |
| :--- | :--- |
| **Java 21 & Spring Boot 3.3.2 Configuration** | [`pom.xml`](file:///d:/Coding/Projects----For%20Resume/Cairn/pom.xml#L9-L19), [`src/main/resources/application.yml`](file:///d:/Coding/Projects----For%20Resume/Cairn/src/main/resources/application.yml#L31-L32) |
| **Pluggable Eviction Strategy Wiring** | [`src/main/java/com/portfolio/cairn/config/CacheConfig.java`](file:///d:/Coding/Projects----For%20Resume/Cairn/src/main/java/com/portfolio/cairn/config/CacheConfig.java#L13-L24) |
| **LRU Doubly-Linked List Implementation** | [`src/main/java/com/portfolio/cairn/engine/evict/LruEvictionPolicy.java`](file:///d:/Coding/Projects----For%20Resume/Cairn/src/main/java/com/portfolio/cairn/engine/evict/LruEvictionPolicy.java#L9-L125) |
| **LFU Frequency Bucket Implementation** | [`src/main/java/com/portfolio/cairn/engine/evict/LfuEvictionPolicy.java`](file:///d:/Coding/Projects----For%20Resume/Cairn/src/main/java/com/portfolio/cairn/engine/evict/LfuEvictionPolicy.java#L10-L118) |
| **Active Adaptive Expiry Sweeper** | [`src/main/java/com/portfolio/cairn/expire/ActiveExpirySweeper.java`](file:///d:/Coding/Projects----For%20Resume/Cairn/src/main/java/com/portfolio/cairn/expire/ActiveExpirySweeper.java#L67-L92) |
| **Consistent Hash Ring with Virtual Nodes** | [`src/main/java/com/portfolio/cairn/sharding/ConsistentHashRing.java`](file:///d:/Coding/Projects----For%20Resume/Cairn/src/main/java/com/portfolio/cairn/sharding/ConsistentHashRing.java#L28-L45) |
| **Proxy Routing with WebClient Connection Pool** | [`src/main/java/com/portfolio/cairn/sharding/NodeRouter.java`](file:///d:/Coding/Projects----For%20Resume/Cairn/src/main/java/com/portfolio/cairn/sharding/NodeRouter.java#L45-L56) |
| **Write-Through and Write-Back Queue Semantics** | [`src/main/java/com/portfolio/cairn/engine/CacheEngine.java`](file:///d:/Coding/Projects----For%20Resume/Cairn/src/main/java/com/portfolio/cairn/engine/CacheEngine.java#L278-L299) |
| **Micrometer & LongAdder Metric Registration** | [`src/main/java/com/portfolio/cairn/metrics/CacheMetricsCollector.java`](file:///d:/Coding/Projects----For%20Resume/Cairn/src/main/java/com/portfolio/cairn/metrics/CacheMetricsCollector.java), [`src/main/java/com/portfolio/cairn/metrics/CairnMeterBinder.java`](file:///d:/Coding/Projects----For%20Resume/Cairn/src/main/java/com/portfolio/cairn/metrics/CairnMeterBinder.java) |
| **Concurrency Stress & Race Condition Tests** | [`src/test/java/com/portfolio/cairn/concurrency/FullSystemConcurrencyTest.java`](file:///d:/Coding/Projects----For%20Resume/Cairn/src/test/java/com/portfolio/cairn/concurrency/FullSystemConcurrencyTest.java), [`src/test/java/com/portfolio/cairn/concurrency/EvictionRaceConditionTest.java`](file:///d:/Coding/Projects----For%20Resume/Cairn/src/test/java/com/portfolio/cairn/concurrency/EvictionRaceConditionTest.java) |
| **Multi-Node Cluster Launch Script** | [`run-cluster.bat`](file:///d:/Coding/Projects----For%20Resume/Cairn/run-cluster.bat) |


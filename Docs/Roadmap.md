# Implementation Roadmap — Cairn (Distributed Cache Service)

## Document Control
* **Document Version:** 0.1.0
* **Status:** Draft
* **Authors:** Portfolio Owner / Technical Architect
* **Milestone Reference:** v0.5.0 (Roadmap Complete)
* **Twin Project Reference:** [Shard Roadmap]()

---

## 1. Git Workflow & Branching Strategy

To maintain a clean and reliable milestone progression, all code additions must adhere to the following git workflow:

```
[main]          <------------------------------ (Milestone Merges Only)
  ^
  | (milestone references: v0.1.0, v0.5.0, v1.0.0)
[test]          <------------------ (RC testing & manual verification)
  ^
  | (successful integration & stress tests)
[develop]       <------ (Local feature branch integration)
  ^
  |
[feature/*]     (Function-level tasks)
```

### Git Transition Gates:
1. **`feature/*` to `develop`:** Triggered when a developer finishes a function-level task. Require code compilation and $100\%$ success of Phase-specific **Standard Unit Tests**.
2. **`develop` to `test`:** Triggered at phase completion. Requires $100\%$ success of **Concurrency Stress Tests** (under high contention) to guarantee there are no race conditions or lock memory leaks.
3. **`test` to `main`:** Triggered at Milestone Completion. Requires final code audit, static analysis checks, and up-to-date documentation.

---

## 2. Phase 1: MVP — Single-Node Engine & Core API

### 2.1 Task Dependency Graph
```
[1.1: Core Storage Engine] ---> [1.2: LRU Strategy] ---> [1.4: Eviction Integration] ---> [1.6: REST API]
                           ---> [1.3: LFU Strategy] ---> [1.5: TTL Expiration]       ---> [1.7: Test Suites]
```

### 2.2 Function-Level Code Tasks
1. **Task 1.1: Core Storage Engine & Records**
   * Implement `CacheEntry.java` record with attributes `value (String)`, `createdTime (long)`, `expiryTime (long)`, `lastAccessTime (long)`, and `accessFrequency (int)`.
   * Implement `CacheEngine.java` containing the main thread-safe index `ConcurrentHashMap<String, CacheEntry>`.
   * Implement `CacheEngine.exists(key)` returning a fast boolean lookup.
   * Implement base `CacheEngine.get(key)` and `CacheEngine.delete(key)` operations (excluding eviction/expiry).
2. **Task 1.2: Eviction Strategy Interface & LRU Implementation**
   * Define `EvictionPolicy.java` interface with methods `onAccess(key)`, `onInsert(key)`, `onRemove(key)`, and `evictVictim()`.
   * Implement `LruEvictionPolicy.java` using a custom doubly-linked list node `LruNode` protected by a local `ReentrantReadWriteLock`.
   * Implement internal node list manipulation methods: `promote(node)`, `remove(node)`, and `addFirst(node)`.
3. **Task 1.3: LFU Eviction Policy Implementation**
   * Implement `LfuEvictionPolicy.java` using a frequency-bucket index.
   * Implement frequency promotion methods: update frequency counter and move keys between lists.
   * Implement minimum frequency tracker pointer (`minFrequency`) to find LFU victim keys in $O(1)$ time.
4. **Task 1.4: Swappable Eviction Strategy Integration**
   * Wire `LruEvictionPolicy` and `LfuEvictionPolicy` as swappable Spring Beans using `@ConditionalOnProperty` in `CacheConfig.java`.
   * Integrate capacity checks inside `CacheEngine.set(key, value)`. If the index size exceeds the maximum key limit, call `evictVictim()`, remove the victim key from the maps, and write the new entry.
5. **Task 1.5: Passive Expiration & Active Sweeper**
   * Integrate passive expiration logic within `CacheEngine.get()` and `CacheEngine.exists()`.
   * Create `ActiveExpirySweeper.java` scheduling sweeps on a dedicated `ScheduledExecutorService` pool.
   * Implement probabilistic sweep method `sweepBatch()` that checks random sub-arrays of keys and loops recursively if expired percentage exceeds $25\%$ of the sample.
6. **Task 1.6: REST API Layer**
   * Implement `CacheController.java` with routes `POST /api/v1/cache`, `GET /api/v1/cache/{key}`, `DELETE /api/v1/cache/{key}`, `GET /api/v1/cache/{key}/exists`, `POST /api/v1/cache/{key}/expire`, and `GET /api/v1/cache/{key}/ttl`.
   * Create `@ControllerAdvice` error handlers to transform `KEY_NOT_FOUND`, `INVALID_TTL`, and `EVICTION_FAILED` internal exceptions into standard HTTP error payloads.

### 2.3 Verification Tasks (Standard vs. Concurrency)
7. **Task 1.7: Verification & Testing**
   * **Standard Unit Tests (Category A):** Validate correctness of LRU doubly-linked list insertion/promotion, LFU frequency bucket boundaries, and passive/active TTL expiries.
   * **Concurrency Stress Tests (Category B):** Spawn parallel threads (e.g., 100 threads using `ExecutorService` and `CountDownLatch`) writing and reading keys simultaneously under a configured capacity of 1,000 keys. Verify that eviction and sweeps never corrupt pointers, drop writes, or raise `NullPointerException`.

### 2.4 Manual Setup Required
* Initialize standard Maven project layout and include Spring Web starter dependencies in `pom.xml`.
* Configure log levels in `logback-spring.xml` to track background active sweeps.

---

## 3. Phase 2: Consistent Hashing & Static Sharding

### 3.1 Task Dependency Graph
```
[Phase 1 Complete] ---> [2.1: Consistent Hashing Ring] ---> [2.2: Routing Layer] ---> [2.4: Route API]
                                                       ---> [2.3: Configuration]  ---> [2.5: Test Suites]
```

### 3.2 Function-Level Code Tasks
1. **Task 2.1: Hashing Ring Data Structure**
   * Implement Murmur3-32 hashing algorithm wrapper.
   * Implement `ConsistentHashRing.java` with internal mapping `TreeMap<Long, String>`.
   * Implement `ConsistentHashRing.addNode(nodeId, virtualNodeCount)` generating multiple hashes per node.
   * Implement `ConsistentHashRing.getNode(key)` returning the nearest virtual node IP from `TreeMap.tailMap(hash)`.
2. **Task 2.2: Routing Proxy Layer**
   * Implement `NodeRouter.java` to act as an HTTP request proxy.
   * Configure asynchronous HTTP caller (`WebClient`) in `NodeRouter` to forward CRUD actions to target node endpoints.
3. **Task 2.3: Static Configuration Bootstrap**
   * Build `NodeConfig.java` to parse list of static IP endpoints and virtual node allocations from `application.yml`.
   * Implement `PostConstruct` initializer to populate the local `ConsistentHashRing` during application boot.
4. **Task 2.4: Cluster Monitoring API**
   * Create `RoutingController.java` with route `GET /api/v1/cluster/health` returning node statuses, and `GET /api/v1/cluster/ring` mapping the ring distribution.

### 3.3 Verification Tasks (Standard vs. Concurrency)
5. **Task 2.5: Verification & Testing**
   * **Standard Unit Tests (Category A):** Validate node hash distributions. Assert that key hashing maps deterministically, and node addition/removal recalculates maps correctly.
   * **Concurrency Stress Tests (Category B):** Launch concurrent API writes across a multi-node setup, verifying proxy connection pools remain stable under load.
   * **Rebalancing Tests (Category C):** Programmatically alter static configurations and measure that the key migration rate matches the expected $1/N$ formula.

### 3.4 Manual Setup Required
* Define static cluster addresses (`cairn.cluster.nodes`) in `application.yml`.
* Setup local test running environment: write shell/batch scripts to boot 3 concurrent JVM instances of Cairn on local ports `8081`, `8082`, and `8083`.

---

## 4. Phase 3: Invalidation Strategies & Observability

### 4.1 Task Dependency Graph
```
[Phase 2 Complete] ---> [3.1: Invalidation API]       ---> [3.3: Metrics Engine] ---> [3.5: Grafana UI]
                       ---> [3.2: Write Semantics]    ---> [3.4: Actuator Hook]  ---> [3.6: Test Suites]
```

### 4.2 Function-Level Code Tasks
1. **Task 3.1: Cache Invalidation Methods**
   * Implement `InvalidationService.java` inside the cache engine.
   * Implement wildcard evaluation method `invalidateByPattern(pattern)` (e.g. scanning segments to match `user:*`).
   * Expose route `POST /api/v1/cache/invalidate` in `CacheController`.
2. **Task 3.2: Write-Through & Write-Back Pipelines**
   * Create a simulated external datasource interface (`MockDatabase`).
   * Implement synchronous write pipeline `writeThrough(key, value)` updating both local cache map and mock database.
   * Implement asynchronous pipeline `writeBack(key, value)` queueing writes to a `LinkedBlockingQueue` drained by dedicated daemon threads.
3. **Task 3.3: Non-Blocking Metrics Aggregation**
   * Implement `CacheMetricsCollector.java` utilizing `java.util.concurrent.atomic.LongAdder` for hits, misses, policy evictions, and expiry counts.
   * Implement operational latency percentile timers ($p50$, $p95$, $p99$) using decaying ring-buffer reservoirs.
4. **Task 3.4: Spring Boot Actuator Mapping**
   * Build custom meter binders in `CacheMetricsCollector` to register Cairn counters with Spring's Actuator registry.
   * Expose endpoints `/actuator/metrics/cairn.cache` and `/actuator/metrics/cairn.cache.latency`.
5. **Task 3.5: Dashboard Visualization**
   * Design dashboard UI layouts and construct Prometheus scrape configurations.
   * Compile Grafana Dashboard JSON templates displaying cluster health cards and hit ratio gauges.

### 4.3 Verification Tasks (Standard vs. Concurrency)
6. **Task 3.6: Verification & Testing**
   * **Standard Unit Tests (Category A):** Verify pattern matches on invalidation, correct write-through sync sequences, and write-back queue drainage checks.
   * **Concurrency Stress Tests (Category B):** Run high-volume write-back loads concurrently with wild-card purges. Verify that queue contention does not block live GET threads, and that Actuator metrics update accurately without causing synchronization delays.

### 4.4 Manual Setup Required
* Configure `application.yml` properties to activate prometheus metrics scrape (`management.endpoints.web.exposure.include=prometheus`).
* Spin up local Prometheus and Grafana Docker instances to verify Actuator metric scrapes and render dashboard visualizations.

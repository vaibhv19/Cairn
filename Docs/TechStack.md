# Tech Stack Document — Cairn (Distributed Cache Service)

## Document Control
* **Document Version:** 0.1.0
* **Status:** Draft
* **Authors:** Portfolio Owner / Technical Architect
* **Target Release:** v0.1.0 (Docs & MVP)
* **Twin Project Reference:** [Shard Tech Stack]()

---

## 1. Core Language & Framework

```
+-------------------------------------------------------+
|                 Java 21 (LTS) JVM                     |
|  +-------------------------------------------------+  |
|  |           Spring Boot 3.3.x (WebMVC)            |  |
|  |  +-------------------------------------------+  |  |
|  |  |           java.util.concurrent            |  |  |
|  |  +-------------------------------------------+  |  |
|  +-------------------------------------------------+  |
+-------------------------------------------------------+
```

### 1.1 Language: Java 21 (LTS)
* **Justification:** Chosen for its modern, robust JVM ecosystem and advanced concurrency features.
* **Key Language Features Utilized:**
  * **Virtual Threads (Project Loom):** Enables a lightweight, high-scale thread-per-request model for REST controller execution, eliminating thread scheduling overhead compared to platform threads.
  * **Pattern Matching & Records:** Used for defining clean, immutable internal models (e.g., Cache Entry metadata, Hashing Ring positions) and simplified branching.
  * **Enhanced JVM Concurrency:** Provides hardware-optimized atomic operations, Memory Barriers, and Compare-And-Swap (CAS) primitives.

### 1.2 Framework: Spring Boot 3.3.x
* **Justification:** Provides the dependency injection engine necessary to decouple the cache core from its swappable components.
* **Core Modules Used:**
  * **Spring Web (WebMVC):** Provides the HTTP API routing and REST request handling layer.
  * **Spring Beans & DI Container:** Implements the Strategy Pattern for eviction policies, making `LruEvictionPolicy` and `LfuEvictionPolicy` swappable beans.
  * **Spring Boot Actuator:** Provides built-in health indicators and hooks for Prometheus metrics collection.

---

## 2. Concurrency Primitives & Internal Storage

To achieve true thread safety under heavy parallel write and read load, Cairn uses highly optimized concurrency primitives rather than crude global synchronization.

| Cache Component | Technology Choice | Architectural Role / Justification |
| :--- | :--- | :--- |
| **Primary Index** | `ConcurrentHashMap<K, V>` | Serves as the key-value registry. Avoids locks for read operations through volatile memory visibility and relies on bucket-level lock-striping for writes, allowing parallel updates. |
| **Eviction Pointers** | `ReentrantReadWriteLock` | Manages synchronization of eviction metadata (such as modifying LRU double-linked lists or LFU frequency buckets). The `ReadLock` allows multiple reads, and the `WriteLock` guarantees exclusive execution during metadata updates. |
| **Active Expiration** | `ScheduledExecutorService` | Hosts a pool of background daemon threads that periodically sweep and evict expired keys without blocking active client request threads. |
| **Operation Counters** | `LongAdder` | Tracks metrics (cache hits, misses, evictions). Highly superior to `AtomicLong` under contention because it uses internal cell arrays to prevent CPU cache-line bouncing. |

---

## 3. Build & Dependency Management

### 3.1 Build Tool: Maven (3.9+)
* **Justification:** Maven's declarative `pom.xml` layout and strict lifecycle phase management make it the industry standard for enterprise Java projects. 
* **Key Plugins:**
  * `spring-boot-maven-plugin`: For compiling, packaging, and running the Spring Boot fat JAR.
  * `maven-surefire-plugin`: To execute unit and concurrent integration tests.

---

## 4. Testing & Verification Stack

Testing concurrent code requires a specialized testing strategy to catch race conditions and memory visibility bugs.

* **Unit Testing:** **JUnit 5** and **AssertJ** for mocking and validation.
* **Concurrent Stress Testing:** Custom test suites executing parallel loops via `CountDownLatch` and `ExecutorService` (e.g., spawning 100 parallel threads executing concurrent write/read operations on the same keys to provoke race conditions).
* **Performance Harness:** **JMH (Java Microbenchmark Harness)** to measure eviction policy operations in-process without network serialization overhead.
* **HTTP Benchmarking:** **`wrk`** or **Apache JMeter** to generate parallel client traffic against HTTP API endpoints and measure tail latencies ($p99$).

---

## 5. Observability & Monitoring

* **Spring Boot Actuator:** Exposes production-ready health and metrics endpoints (`/actuator/health`, `/actuator/metrics`).
* **Micrometer Core:** Standardized instrumentation library that bridges application counters directly to Prometheus-compatible formats.

---

## 6. Technology Non-Goals (MVP / Phase 2)

* **No Distributed Consensus Engine (e.g., ZooKeeper/Consul):** Excluded to avoid system complexity. Routing and cluster topology remain statically compiled within local node configuration.
* **No Database/Persistence Driver:** No JPA, Hibernate, or JDBC. The cache engine does not hook into disk storage.

---

## 7. "Why Not" Analysis (Architectural Trade-Offs)

### 7.1 Why `ConcurrentHashMap`-based sharding over a full Distributed Coordination Framework (e.g., ZooKeeper)?
* **Rejected Alternative:** Incorporating Apache ZooKeeper to dynamically track node membership and coordinate cluster state.
* **Trade-Off Analysis:**
  * *Complexity:* ZooKeeper introduces heavy runtime dependencies, dynamic consensus management, and heartbeat overhead.
  * *Scale:* Because Cairn is deliberately scoped to static sharding in Phase 2, a client-side Consistent Hashing Ring utilizing local Spring Configuration (`application.yml`) is completely sufficient. It provides deterministic $O(1)$ routing without network hops to a coordination server, maintaining focus on raw cache performance.

### 7.2 Why `ReentrantReadWriteLock` for eviction instead of standard `synchronized` blocks?
* **Rejected Alternative:** Using `synchronized` on cache access methods.
* **Trade-Off Analysis:**
  * *Concurrency:* A `synchronized` block serializes all access, turning a multi-threaded system into a single-threaded bottleneck.
  * *Read/Write Balance:* Since cache operations are heavily read-biased (GET operations are much more frequent than SET), `ReentrantReadWriteLock` allows hundreds of threads to execute reads in parallel, only blocking when an eviction metadata write is required.

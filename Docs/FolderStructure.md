# Folder Structure Document — Cairn (Distributed Cache Service)

## Document Control
* **Document Version:** 0.1.0
* **Status:** Draft
* **Authors:** Portfolio Owner / Technical Architect
* **Milestone Reference:** v0.1.0 (Docs Complete)
* **Twin Project Reference:** [Shard Folder Structure]()

---

## 1. Project Directory Layout

Cairn follows the standard Maven directory structure, separating application logic (`src/main`) from testing suites (`src/test`). 

```
cairn/
├── .git/
├── Docs/                              # v0.1.0 Complete Documentation Suite
│   ├── Cairn — Feature List.txt
│   ├── PRD.md
│   ├── TechStack.md
│   ├── SystemArchitecture.md
│   ├── AppFlow.md
│   ├── UIDesign.md
│   ├── FolderStructure.md
│   ├── DBSchema.md
│   └── APIContracts.md
├── pom.xml                            # Maven configuration file
└── src/
    ├── main/
    │   ├── java/
    │   │   └── com/
    │   │       └── portfolio/
    │   │           └── cairn/
    │   │               ├── CairnApplication.java      # Application entrypoint
    │   │               ├── config/                    # Spring Beans & configs
    │   │               │   ├── CacheConfig.java
    │   │               │   └── SchedulerConfig.java
    │   │               ├── controller/                # REST Controllers (API Layer)
    │   │               │   ├── CacheController.java
    │   │               │   └── RoutingController.java # [Phase 2] Routing endpoint
    │   │               ├── engine/                    # Core Cache Engine
    │   │               │   ├── CacheEngine.java       # Coordinates storage & policies
    │   │               │   ├── CacheEntry.java        # Core in-memory data record
    │   │               │   └── evict/                 # Eviction Policy Strategy
    │   │               │       ├── EvictionPolicy.java# Strategy interface
    │   │               │       ├── LruEvictionPolicy.java
    │   │               │       └── LfuEvictionPolicy.java
    │   │               ├── expire/                    # Key Expiration Subsystem
    │   │               │   └── ActiveExpirySweeper.java # Background sweep daemon
    │   │               ├── sharding/                  # [Phase 2] Consistent Hashing
    │   │               │   ├── ConsistentHashRing.java
    │   │               │   ├── NodeRouter.java        # Client-side / proxy routing
    │   │               │   └── NodeConfig.java
    │   │               └── metrics/                   # [Phase 3] Actuator & Counters
    │   │                   ├── CacheMetricsCollector.java
    │   │                   └── InvalidationService.java
    │   └── resources/
    │       ├── application.yml        # Service properties (ports, static nodes)
    │       └── logback-spring.xml     # Logging configuration
    └── test/
        └── java/
            └── com/
                └── portfolio/
                    └── cairn/
                        ├── unit/                      # Standard unit tests
                        │   ├── CacheEngineTest.java
                        │   ├── LruEvictionTest.java
                        │   └── LfuEvictionTest.java
                        └── concurrency/               # High-contention stress tests
                            ├── ConcurrentReadWriteTest.java
                            ├── EvictionRaceConditionTest.java
                            └── ExpirySweepSafetyTest.java
```

---

## 2. Component Directory Descriptions

### 2.1 Cache Engine & Strategy (`/engine`)
* **`CacheEngine.java`**: The central coordinator containing storage operations. Directly references the pluggable eviction strategy bean and orchestrates key modifications.
* **`CacheEntry.java`**: Represents the in-memory object stored inside the primary map. Holds the user value payload and metadata (creation time, TTL, last-access timestamp, access frequency).
* **`evict/`**: Implements the Strategy Pattern. Contains the interface and swappable Spring component implementations (`LruEvictionPolicy`, `LfuEvictionPolicy`) to keep eviction logic modular.

### 2.2 API Layer (`/controller`)
* **`CacheController.java`**: Houses REST routes, mapping HTTP requests (`GET`, `POST`, `DELETE`) to the `CacheEngine` operations.
* **`RoutingController.java`** *[Phase 2]*: Handles routing coordination and node status checks in the static cluster environment.

### 2.3 Expiration & Sweeps (`/expire`)
* Contains tasks and thread pool handlers running background tasks. Resolves active key expirations asynchronously using `ScheduledExecutorService` parameters.

### 2.4 Sharding & Consistent Hashing (`/sharding`) *[Phase 2]*
* Contains files representing virtual node configuration mapping, key hashing routines, node target calculation on the hashing ring, and node failover simulations.

### 2.5 Metrics & Invalidation (`/metrics`) *[Phase 3]*
* Contains metrics counters and invalidation logic to clear cache contents manually or dynamically.

---

## 3. Test Isolation Directory Design

Because Cairn’s core goal is to verify thread safety and lock performance under true parallelism, tests are strictly split into two modules to preserve fast CI execution times:

1. **Standard Unit Tests (`/test/unit/`):** Contains fast, single-threaded functional verification tests. Confirms correctness of LRU/LFU lists, key entry updates, and expiration math. Executes in milliseconds.
2. **Concurrency & Stress Tests (`/test/concurrency/`):** Contains multi-threaded tests designed to expose race conditions. These tests spawn large concurrent pools of reader/writer threads that bombard the same key segments, verifying that list pointers are not corrupted and that key limits are strictly maintained. These stress tests take longer to run and are separated to optimize standard development builds.

---

## 4. `/docs` Folder Inventory

The `/Docs` folder contains the complete, MAANG-level v0.1.0 documentation set required for the Cairn project milestone:
* **`PRD.md`**: Core product goals, requirements, success metrics, and twin comparison scope.
* **`TechStack.md`**: Architectural justifications for Java 21, Spring Boot, `ConcurrentHashMap`, and locking choices.
* **`SystemArchitecture.md`**: Internal component layouts, thread models, locking barriers, and sharding topology.
* **`AppFlow.md`**: Request lifecycles, contention sequences, background sweep execution, and data flow steps.
* **`UIDesign.md`**: REST API JSON structures and Phase 3 operator metrics dashboard wireframe.
* **`FolderStructure.md`**: Application package layouts, test isolation mapping, and document directories (this document).
* **`DBSchema.md`**: Internal in-memory record layout and structural non-goals.
* **`APIContracts.md`**: Complete URL routing, JSON payloads, and HTTP error mappings across all phases.

# Folder Structure Document — Cairn (Distributed Cache Service)

## Document Control
* **Document Version:** 0.1.0
* **Status:** Complete
* **Authors:** Portfolio Owner / Technical Architect
* **Milestone Reference:** v1.0.0 (Phase 3 Complete)
* **Twin Project Reference:** [Shard](https://github.com/vaibhv19/Shard)

---

## 1. Project Directory Layout

Cairn follows the standard Maven directory structure, separating application logic (`src/main`) from testing suites (`src/test`). 

```
cairn/
├── .git/
├── Docs/                              # Complete Documentation Suite
│   ├── assets/                        # Architecture & flow diagrams
│   │   ├── concurrency_thread_interaction.mermaid
│   │   ├── consistent_hashing_ring.mermaid
│   │   └── dashboard_layout.mermaid
│   ├── grafana-dashboard.json         # Prometheus / Grafana dashboard template
│   ├── APIContracts.md
│   ├── AppFlow.md
│   ├── FolderStructure.md
│   ├── LEARNING_HANDBOOK.md
│   ├── PRD.md
│   ├── Roadmap.md
│   ├── SystemArchitecture.md
│   ├── TechStack.md
│   └── UIDesign.md
├── pom.xml                            # Maven configuration file
├── run-cluster.bat                    # Multi-node local cluster startup script
└── src/
    ├── main/
    │   ├── java/
    │   │   └── com/
    │   │       └── portfolio/
    │   │           └── cairn/
    │   │               ├── CairnApplication.java      # Application entrypoint
    │   │               ├── config/                    # Spring Beans & configs
    │   │               │   └── CacheConfig.java
    │   │               ├── engine/                    # Core Cache Engine
    │   │               │   ├── CacheEngine.java       # Coordinates storage & policies
    │   │               │   ├── CacheEntry.java        # Core in-memory data record
    │   │               │   ├── InvalidationService.java # Pattern-based invalidations
    │   │               │   ├── MockDatabase.java      # Mock persistence store
    │   │               │   └── evict/                 # Eviction Policy Strategy
    │   │               │       ├── EvictionPolicy.java# Strategy interface
    │   │               │       ├── LruEvictionPolicy.java
    │   │               │       └── LfuEvictionPolicy.java
    │   │               ├── exception/                 # Custom domain exceptions
    │   │               │   ├── EvictionFailedException.java
    │   │               │   ├── InvalidTtlException.java
    │   │               │   └── KeyNotFoundException.java
    │   │               ├── expire/                    # Key Expiration Subsystem
    │   │               │   └── ActiveExpirySweeper.java # Background sweep daemon
    │   │               ├── metrics/                   # Actuator & Counters
    │   │               │   ├── CacheMetricsCollector.java
    │   │               │   └── CairnMeterBinder.java
    │   │               ├── sharding/                  # Consistent Hashing & Routing
    │   │               │   ├── ConsistentHashRing.java
    │   │               │   ├── NodeConfig.java
    │   │               │   └── NodeRouter.java        # Proxy routing layer
    │   │               └── web/                       # REST API Layer
    │   │                   ├── CacheController.java
    │   │                   ├── CacheDtos.java
    │   │                   ├── ClusterDtos.java
    │   │                   ├── GlobalExceptionHandler.java
    │   │                   └── RoutingController.java # Cluster health endpoints
    │   └── resources/
    │       └── application.yml        # Service properties (ports, static nodes)
    └── test/
        └── java/
            └── com/
                └── portfolio/
                    └── cairn/
                        ├── unit/                      # Standard unit tests
                        │   ├── CacheControllerTest.java
                        │   ├── CacheEngineTest.java
                        │   ├── CacheEvictionIntegrationTest.java
                        │   ├── CacheExpiryTest.java
                        │   ├── CacheInvalidationTest.java
                        │   ├── CacheMetricsActuatorTest.java
                        │   ├── CacheMetricsCollectorTest.java
                        │   ├── ConsistentHashRingTest.java
                        │   ├── LruEvictionTest.java
                        │   ├── LfuEvictionTest.java
                        │   ├── NodeConfigTest.java
                        │   ├── NodeRouterTest.java
                        │   ├── RoutingControllerTest.java
                        │   └── WriteSemanticsTest.java
                        └── concurrency/               # High-contention stress tests
                            ├── ConcurrentEvictionIntegrationTest.java
                            ├── ConcurrentExpiryTest.java
                            ├── EvictionRaceConditionTest.java
                            └── FullSystemConcurrencyTest.java
```

---

## 2. Component Directory Descriptions

### 2.1 Cache Engine & Strategy (`/engine`)
* **`CacheEngine.java`**: The central coordinator containing storage operations. Directly references the pluggable eviction strategy bean and orchestrates key modifications.
* **`CacheEntry.java`**: Represents the in-memory object stored inside the primary map. Holds the user value payload and metadata (creation time, TTL, last-access timestamp, access frequency).
* **`InvalidationService.java`**: Handles exact and wildcard key pattern invalidations across cache partitions.
* **`MockDatabase.java`**: In-memory database simulation for validating write-through and write-back pipelines.
* **`evict/`**: Implements the Strategy Pattern. Contains the interface and swappable Spring component implementations (`LruEvictionPolicy`, `LfuEvictionPolicy`) to keep eviction logic modular.

### 2.2 API Layer (`/web`)
* **`CacheController.java`**: Houses REST routes, mapping HTTP requests (`GET`, `POST`, `DELETE`) to the `CacheEngine` operations via NodeRouter.
* **`CacheDtos.java`**: Standard DTO requests and responses with Jakarta Validation constraints.
* **`ClusterDtos.java`**: DTOs for cluster health and ring status endpoints.
* **`GlobalExceptionHandler.java`**: Global exception mapper mapping custom exceptions to standard HTTP error structures.
* **`RoutingController.java`**: Handles routing coordination and node status checks in the static cluster environment.

### 2.3 Expiration & Sweeps (`/expire`)
* Contains tasks and thread pool handlers running background tasks. Resolves active key expirations asynchronously using `ScheduledExecutorService` parameters.

### 2.4 Sharding & Consistent Hashing (`/sharding`)
* Contains files representing virtual node configuration mapping, key hashing routines (`ConsistentHashRing`), static cluster parsing (`NodeConfig`), and node proxy routing (`NodeRouter`).

### 2.5 Metrics & Telemetry (`/metrics`)
* Contains non-blocking metrics counters (`CacheMetricsCollector`) and Micrometer binder (`CairnMeterBinder`) to expose cache metrics to Prometheus and Actuator.

---

## 3. Test Isolation Directory Design

Because Cairn’s core goal is to verify thread safety and lock performance under true parallelism, tests are strictly split into two modules to preserve fast CI execution times:

1. **Standard Unit Tests (`/test/unit/`):** Contains fast, single-threaded functional verification tests. Confirms correctness of LRU/LFU lists, key entry updates, and expiration math. Executes in milliseconds.
2. **Concurrency & Stress Tests (`/test/concurrency/`):** Contains multi-threaded tests designed to expose race conditions. These tests spawn large concurrent pools of reader/writer threads that bombard the same key segments, verifying that list pointers are not corrupted and that key limits are strictly maintained. These stress tests take longer to run and are separated to optimize standard development builds.

---

## 4. `/docs` Folder Inventory

The `/Docs` folder contains the documentation set for the Cairn project:
* **`PRD.md`**: Core product goals, requirements, success metrics, and twin comparison scope.
* **`TechStack.md`**: Architectural justifications for Java 21, Spring Boot, `ConcurrentHashMap`, and locking choices.
* **`SystemArchitecture.md`**: Internal component layouts, thread models, locking barriers, and sharding topology.
* **`AppFlow.md`**: Request lifecycles, contention sequences, background sweep execution, and data flow steps.
* **`UIDesign.md`**: REST API JSON structures and Phase 3 operator metrics dashboard wireframe.
* **`FolderStructure.md`**: Application package layouts, test isolation mapping, and document directories (this document).
* **`APIContracts.md`**: Complete URL routing, JSON payloads, and HTTP error mappings across all phases.
* **`Roadmap.md`**: Implementation roadmap, task dependency graphs, and git workflow progression.
* **`LEARNING_HANDBOOK.md`**: Concurrency insights, architectural retrospectives, and Shard vs. Cairn comparative analysis.
* **`grafana-dashboard.json`**: Ready-to-import Grafana dashboard panel layout for Prometheus metrics.

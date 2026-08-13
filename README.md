# Cairn

Cairn is a distributed, high-performance in-memory key-value cache service built on Java 21 and Spring Boot. It serves as a JVM-based parallel execution cache engine, designed to showcase thread safety, deterministic sharding, and real-time observability under high-concurrency loads.

Unlike its twin project [Shard](https://github.com/vaibhv19/Shard) (built with Python/Django), which runs on a single-threaded event loop and relies on process-level scaling, Cairn utilizes OS-level multi-threading and fine-grained lock-striping. This allows multiple concurrent reader and writer threads to execute cache operations at the same physical instance in time without encountering GIL limitations.

---

## Component Architecture

Below is the component layout of a single Cairn node and its interaction within a sharded cluster:

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

---

## Features

- **Core Storage & Expiration:** In-memory storage backed by a segmented thread-safe map. Passive TTL checks are executed on access, supplemented by an active background sweeper thread pooling expired keys.
- **Eviction Strategy:** Pluggable, thread-safe cache eviction algorithms (LRU and LFU) synchronized using `ReentrantReadWriteLock` to maintain pointer integrity.
- **Consistent Hashing Ring:** Client-side proxy routing that maps cache operations to target nodes. Built with Murmur3-32 unsigned hashing on a virtual node ring (150 virtual nodes per physical instance).
- **Invalidation Engine:** Supports exact-key, wildcard prefix-matching (e.g., `user:*`), and cluster-wide flush (`*`) invalidations, broadcasted asynchronously to all nodes.
- **Write Pipelines:** Pluggable write semantics including synchronous `write-through` (blocking until DB confirms) and asynchronous `write-back` backed by a queue-draining daemon thread.
- **Observability:** Custom meter binders registering hits, misses, policy/TTL evictions, and decaying ring-buffer latency percentiles ($p50$, $p95$, $p99$) through Micrometer, Spring Boot Actuator, and Prometheus.

---

## Tech Stack

- **Runtime:** Java 21 (LTS)
- **Framework:** Spring Boot 3.3.x (WebMVC)
- **Concurrency & Storage:**
  - `ConcurrentHashMap` for lock-free primary index lookups.
  - `ReentrantReadWriteLock` to isolate eviction pointer adjustments.
  - `ScheduledExecutorService` for background expiration sweepers.
  - `LongAdder` for contention-free statistics counters.
  - `LinkedBlockingQueue` for asynchronous write-back execution.
- **Observability:** Micrometer Core, Spring Boot Actuator, Prometheus, and Grafana.

---

## API Quick Reference

| Endpoint | Method | Description |
| :--- | :--- | :--- |
| `/api/v1/cache` | `POST` | Insert or update a cache key-value pair (accepts optional `ttl` in seconds) |
| `/api/v1/cache/{key}` | `GET` | Retrieve value and remaining TTL of a cache key |
| `/api/v1/cache/{key}` | `DELETE` | Delete a key from the cache |
| `/api/v1/cache/{key}/exists` | `GET` | Check if a key exists without updating access policies |
| `/api/v1/cache/{key}/expire` | `POST` | Update the TTL expiration of a key |
| `/api/v1/cache/{key}/ttl` | `GET` | Retrieve remaining TTL in seconds (-1 if persistent) |
| `/api/v1/cache/invalidate` | `POST` | Invalidate a key or key-pattern (e.g., `user:*` or `*`) |
| `/api/v1/cluster/health` | `GET` | Check cluster node status (Active/Offline) |
| `/api/v1/cluster/ring` | `GET` | Retrieve the consistent hashing ring node positions map |
| `/actuator/metrics/cairn.cache` | `GET` | Retrieve operational stats (hits, misses, evictions) |
| `/actuator/metrics/cairn.cache.latency` | `GET` | Retrieve p50, p95, and p99 request latencies |
| `/actuator/prometheus` | `GET` | Scrape endpoint for Prometheus server |

*For complete contract models, error payloads, and HTTP status codes, see [APIContracts.md](file:///d:/Coding/Projects----For%20Resume/Cairn/Docs/APIContracts.md).*

---

## Running Locally

### Prerequisites
- JDK 21
- Maven 3.9+
- Docker (optional, for Prometheus/Grafana stack)

### 1. Build the Service
Compile and package the Spring Boot executable fat JAR:
```bash
mvn clean package -DskipTests
```

### 2. Configure Node settings
Adjust the static cluster layout or the local node ID inside [application.yml](file:///d:/Coding/Projects----For%20Resume/Cairn/src/main/resources/application.yml):
```yaml
cairn:
  cache:
    max-size: 10000
    eviction-policy: lru
  cluster:
    local-node-id: Node-A
    nodes:
      - nodeId: Node-A
        address: http://127.0.0.1:8081
        virtualNodes: 150
      - nodeId: Node-B
        address: http://127.0.0.1:8082
        virtualNodes: 150
```

### 3. Spin Up Local Cluster Instances
Run the cluster helper batch script to start 3 node instances on ports `8081`, `8082`, and `8083`:
```bash
./run-cluster.bat
```

### 4. Metrics Dashboard Integration
Expose `/actuator/prometheus` endpoints and hook them into Prometheus. A complete Grafana panel layout is provided in [grafana-dashboard.json](file:///d:/Coding/Projects----For%20Resume/Cairn/Docs/grafana-dashboard.json).

---

## Testing

Testing is isolated into standard unit/integration checks and concurrency-contention suites:

### Category A: Unit and Integration Tests
Validate logic, eviction correctness, and HTTP endpoints in isolation:
```bash
mvn test -Dtest=*Test
```

### Category B: Concurrency Stress Tests
Verify safety under concurrent thread schedules:
```bash
mvn test -Dtest=*Concurrency*
```

---

## Twin Project

- **[Shard](https://github.com/vaibhv19/Shard):** A Python-based single-threaded event-loop cache service, serving as an architectural comparison counterpart.

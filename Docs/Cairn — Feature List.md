# Feature List — Cairn (Distributed Cache Service)

## Document Control
* **Document Version:** 0.1.0
* **Status:** Draft
* **Authors:** Portfolio Owner / Technical Architect
* **Milestone Reference:** v0.1.0 (Docs Complete)
* **Twin Project Reference:** [Shard Feature List]()

---

## Project Context
* **Project Name:** Cairn (Distributed Cache Service — Spring Boot/Java build)
* **Technology Stack:** Java 21 (LTS) + Spring Boot 3.3.x + `java.util.concurrent` (`ConcurrentHashMap`, `ReentrantReadWriteLock`, `ScheduledExecutorService`)
* **Core Concurrency Differentiator:** Shares the identical custom cache engine and functional requirements as Shard (Django/Python). Concurrency safety is implemented via Java's native parallel OS-level multi-threading rather than Python's GIL-limited threading model, allowing true parallel CPU scaling comparison.

---

## 1. Phase 1: MVP (Single-Node Core & REST API)

* **In-Memory Key-Value Store Core:** A thread-safe indexing map (utilizing `ConcurrentHashMap` with segment-level lock-striping) supporting basic cache operations: `SET`, `GET`, `DELETE`, and `EXISTS`. Operates single-node only with no disk persistence.
* **Pluggable Eviction Policies:** swappable LRU (Least Recently Used) and LFU (Least Frequently Used) eviction algorithms. Designed using the Strategy Pattern with swappable Spring Beans, configurable per cache instance.
* **TTL Expiry:** Key-level expiration configured at write time. Uses a two-tier cleanup mechanism: active expiration via a background `ScheduledExecutorService` sweep thread, and passive expiration checked synchronously on key access.
* **Concurrency Safety:** Core correctness under parallel multi-threaded access. Ensures eviction and expiration operations remain safe under concurrent race conditions (no data pointer corruption, no invalid evictions).
* **Client-Facing API:** Rest controller endpoints exposing cache operations:
  * `POST /api/v1/cache` (SET)
  * `GET /api/v1/cache/{key}` (GET)
  * `DELETE /api/v1/cache/{key}` (DELETE)
  * `GET /api/v1/cache/{key}/exists` (EXISTS)
  * `POST /api/v1/cache/{key}/expire` (EXPIRE)
  * `GET /api/v1/cache/{key}/ttl` (TTL)

---

## 2. Phase 2: Sharding & Distribution

* **Consistent Hashing Ring:** Implementation of a consistent hashing ring using a `TreeMap` structure. Deterministically routes keys to specific cache node instances on the ring, using virtual nodes (e.g., 150 per physical node) to maintain uniform distribution.
* **Routing Layer:** A client-side or proxy routing component that intercepts operations, hashes keys, and forwards the command to the target cache node.
* **Static Node Membership:** Static cluster topology configuration read directly from `application.yml`. Dynamic consensus/discovery (such as Gossip or Raft) is explicitly out of scope.
* **Rebalancing Behavior:** Deterministic key migration when static node mapping is altered, achieving a key transfer rate of approximately $1/N$ during cluster resizing.

---

## 3. Phase 3: Invalidation Strategies & Metrics Dashboard

* **Cache Invalidation:** Explicit cache invalidation routes (wildcard/pattern matching, flush-all), write-through sync writing paths, and write-back asynchronous queue-based write paths.
* **Metrics Collection:** Real-time tracking of operational metrics using Spring Boot Actuator and Micrometer:
  * Cache hit/miss counters and hit ratios (using lock-free `LongAdder` cells).
  * Eviction counts (split by TTL vs. capacity policy).
  * Per-node CPU, memory, and key-density statistics.
  * Operation latency distributions ($p50$, $p95$, $p99$ percentiles).
* **Metrics Dashboard:** Visual aggregator dashboard combining metrics from all nodes to compare LRU vs. LFU hit rates and monitor cluster load balancing.

# Interface & UI Design Document — Cairn (Distributed Cache Service)

## Document Control
* **Document Version:** 0.1.0
* **Status:** Draft
* **Authors:** Portfolio Owner / Technical Architect
* **Target Release:** v0.1.0 (Docs & MVP)
* **Twin Project Reference:** [Shard UI Design]()

---

## 1. Scope of Interfaces

Cairn is a backend system utility. It is consumed programmatically. Its interfaces are divided into two distinct categories based on phase boundaries:

1. **REST API Interface (MVP & Phase 2):** The primary interface for all CRUD cache interactions. Designed for maximum developer ergonomics, performance, and descriptive status reporting.
2. **Metrics & Performance Dashboard (Phase 3):** An operator/developer facing dashboard to compare eviction policies (LRU vs. LFU) under load and monitor node cluster balance.

> [!NOTE]
> During MVP and Phase 2, Cairn has **no graphical user interface**. It operates strictly as an API-only service. 

---

## 2. REST API Contract & Payloads (MVP)

All REST request bodies and responses are formatted as JSON payloads.

### 2.1 SET Cache Entry
* **Endpoint:** `POST /api/v1/cache`
* **Request Header:** `Content-Type: application/json`
* **Request Payload:**
  ```json
  {
    "key": "user:profile:1004",
    "value": "{\"name\": \"Alice\", \"role\": \"admin\"}",
    "ttl": 3600
  }
  ```
  *(Note: `ttl` is optional. If omitted, the key has no expiry unless forced by eviction policies.)*
* **Response (New Insert):** `201 Created`
  ```json
  {
    "status": "success",
    "message": "Key created successfully",
    "key": "user:profile:1004",
    "expiry": "2026-08-13T10:12:11Z"
  }
  ```
* **Response (Overwrite Existing):** `200 OK`
  ```json
  {
    "status": "success",
    "message": "Key updated successfully",
    "key": "user:profile:1004",
    "expiry": "2026-08-13T10:12:11Z"
  }
  ```
* **Error Response (Capacity Eviction Failure / Out of Memory):** `507 Insufficient Storage`
  ```json
  {
    "status": "error",
    "errorCode": "EVICTION_FAILED",
    "message": "Cache capacity reached and eviction was unable to free memory.",
    "timestamp": "2026-08-13T09:12:11.452Z"
  }
  ```

---

### 2.2 GET Cache Entry
* **Endpoint:** `GET /api/v1/cache/{key}`
* **Response (Cache Hit):** `200 OK`
  ```json
  {
    "key": "user:profile:1004",
    "value": "{\"name\": \"Alice\", \"role\": \"admin\"}",
    "ttl_remaining": 3598
  }
  ```
* **Response (Cache Miss / Expired):** `404 Not Found`
  ```json
  {
    "status": "error",
    "errorCode": "KEY_NOT_FOUND",
    "message": "Requested key does not exist or has expired.",
    "timestamp": "2026-08-13T09:12:15.112Z"
  }
  ```

---

### 2.3 DELETE Cache Entry
* **Endpoint:** `DELETE /api/v1/cache/{key}`
* **Response (Delete Successful):** `204 No Content` *(No response body)*
* **Response (Key Absent):** `404 Not Found`
  ```json
  {
    "status": "error",
    "errorCode": "KEY_NOT_FOUND",
    "message": "Cannot delete key: key does not exist.",
    "timestamp": "2026-08-13T09:12:20.005Z"
  }
  ```

---

### 2.4 EXISTS / EXPIRE / TTL Operations
* **EXISTS:** `GET /api/v1/cache/{key}/exists`
  * **Response (Hit):** `200 OK` -> `{ "key": "user:profile:1004", "exists": true }`
  * **Response (Miss):** `200 OK` -> `{ "key": "user:profile:1004", "exists": false }`
* **EXPIRE:** `POST /api/v1/cache/{key}/expire`
  * **Request Payload:** `{ "ttl": 300 }`
  * **Response (Hit):** `200 OK` -> `{ "key": "user:profile:1004", "ttl_updated": 300 }`
  * **Response (Miss):** `404 Not Found` -> *(Standard error payload)*
* **TTL:** `GET /api/v1/cache/{key}/ttl`
  * **Response (Hit):** `200 OK` -> `{ "key": "user:profile:1004", "ttl_remaining": 298 }`
  * **Response (No Expiry):** `200 OK` -> `{ "key": "user:profile:1004", "ttl_remaining": -1 }`
  * **Response (Miss):** `404 Not Found`

---

## 3. Metrics Dashboard Wireframe (Phase 3)

The Phase 3 dashboard is a diagnostic tool used to compare performance. For example, developers can run identical workloads and toggle eviction settings between LRU and LFU to see which policy yields a better hit ratio.

### 3.1 Dashboard Layout Design (ASCII Wireframe)

```
========================================================================================
 CAIRN CACHE - DISTRIBUTED METRICS DASHBOARD                               [Refresh: Auto]
========================================================================================
 [ Cluster Configuration: 3 Nodes (Static) ]  [ Eviction Policy Active: LRU ]
 
 +---------------------------+ +---------------------------+ +---------------------------+
 | Node A: http://127.0.0.1  | | Node B: http://127.0.0.1  | | Node C: http://127.0.0.1  |
 | Status: ACTIVE            | | Status: ACTIVE            | | Status: ACTIVE            |
 | Keys: 12,450 / 20,000     | | Keys: 12,110 / 20,000     | | Keys: 12,890 / 20,000     |
 | CPU: 12% | Mem: 34MB/128MB| | CPU: 14% | Mem: 32MB/128MB| | CPU: 11% | Mem: 36MB/128MB|
 +---------------------------+ +---------------------------+ +---------------------------+

----------------------------------------------------------------------------------------
 OPERATIONAL METRICS OVERVIEW
----------------------------------------------------------------------------------------
 Hits / Misses Ratio Chart (Aggregated)             Operation Latency Percentiles (p99)
 +-------------------------------------------+     +-----------------------------------+
 | 85% [########################-------] 15% |     | GET: 1.12 ms                      |
 |      Cache Hits           Cache Misses    |     | SET: 2.45 ms                      |
 +-------------------------------------------+     | DELETE: 0.98 ms                   |
                                                   +-----------------------------------+

 Eviction Event Counters
 +-------------------------------------------------------------------------------------+
 | Active TTL Sweeper Expirations: [ 1,452 ]  | Capacity Policy Evictions: [ 451 ]     |
 +-------------------------------------------------------------------------------------+

----------------------------------------------------------------------------------------
 SYSTEM LOGS & METRICS STREAM
 [09:12:00] [Node A] Policy Eviction triggered - Expelled key [user:session:990]
 [09:12:05] [Node B] Background Expiry Sweep complete. 12 stale keys reclaimed.
========================================================================================
```

### 3.2 Key Visualizations & Charts
1. **Cluster Health Cards:** Shows individual CPU, Memory, and Active Key Count to easily spot ring distribution issues (hot spots) or node outages.
2. **Hit/Miss Gauge:** Displays the hit/miss ratio, helping developers tune eviction sizing and choices.
3. **Latency distribution graph:** Shows $p50$, $p95$, and $p99$ metrics to evaluate the performance impact of lock contention on live traffic.
4. **Eviction Breakdown Counter:** Distinguishes between keys that naturally expired (TTL) and keys forced out by memory limits (LRU/LFU), showing if the cache is undersized.

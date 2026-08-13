# API Contracts Document — Cairn (Distributed Cache Service)

## Document Control
* **Document Version:** 0.1.0
* **Status:** Draft
* **Authors:** Portfolio Owner / Technical Architect
* **Target Release:** v0.1.0 (Docs & MVP)
* **Twin Project Reference:** [Shard API Contracts]()

---

## 1. MVP REST API Endpoints

The core single-node cache operations are exposed via standard REST paths. All request/response payloads are serialized using JSON.

### 1.1 SET Key
* **HTTP Method:** `POST`
* **Path:** `/api/v1/cache`
* **Headers:** `Content-Type: application/json`
* **Request Body:**
  ```json
  {
    "key": "session:token:x901",
    "value": "usr_7721",
    "ttl": 300
  }
  ```
  *(Note: `ttl` is optional. Value is in seconds.)*
* **Response (New Insert):**
  * **Status Code:** `201 Created`
  * **Response Body:**
    ```json
    {
      "status": "success",
      "message": "Key created successfully",
      "key": "session:token:x901",
      "expiry": "2026-08-13T10:13:29Z"
    }
    ```
* **Response (Update Existing):**
  * **Status Code:** `200 OK`
  * **Response Body:**
    ```json
    {
      "status": "success",
      "message": "Key updated successfully",
      "key": "session:token:x901",
      "expiry": "2026-08-13T10:13:29Z"
    }
    ```
* **Response (Validation Error - Invalid TTL):**
  * **Status Code:** `400 Bad Request`
  * **Response Body:**
    ```json
    {
      "status": "error",
      "errorCode": "INVALID_TTL",
      "message": "TTL must be a positive integer.",
      "timestamp": "2026-08-13T09:13:29.112Z"
    }
    ```

---

### 1.2 GET Key
* **HTTP Method:** `GET`
* **Path:** `/api/v1/cache/{key}`
* **Response (Hit):**
  * **Status Code:** `200 OK`
  * **Response Body:**
    ```json
    {
      "key": "session:token:x901",
      "value": "usr_7721",
      "ttl_remaining": 298
    }
    ```
* **Response (Miss / Expired):**
  * **Status Code:** `404 Not Found`
  * **Response Body:**
    ```json
    {
      "status": "error",
      "errorCode": "KEY_NOT_FOUND",
      "message": "Requested key does not exist or has expired.",
      "timestamp": "2026-08-13T09:13:31.002Z"
    }
    ```

---

### 1.3 DELETE Key
* **HTTP Method:** `DELETE`
* **Path:** `/api/v1/cache/{key}`
* **Response (Deleted):**
  * **Status Code:** `204 No Content` *(Empty Body)*
* **Response (Miss):**
  * **Status Code:** `404 Not Found`
  * **Response Body:**
    ```json
    {
      "status": "error",
      "errorCode": "KEY_NOT_FOUND",
      "message": "Cannot delete key: key does not exist.",
      "timestamp": "2026-08-13T09:13:35.000Z"
    }
    ```

---

### 1.4 EXISTS Check
* **HTTP Method:** `GET`
* **Path:** `/api/v1/cache/{key}/exists`
* **Response (Exists):**
  * **Status Code:** `200 OK`
  * **Response Body:**
    ```json
    {
      "key": "session:token:x901",
      "exists": true
    }
    ```
* **Response (Does Not Exist):**
  * **Status Code:** `200 OK`
  * **Response Body:**
    ```json
    {
      "key": "session:token:x901",
      "exists": false
    }
    ```

---

### 1.5 EXPIRE (Set TTL on existing key)
* **HTTP Method:** `POST`
* **Path:** `/api/v1/cache/{key}/expire`
* **Request Body:**
  ```json
  {
    "ttl": 600
  }
  ```
* **Response (Success):**
  * **Status Code:** `200 OK`
  * **Response Body:**
    ```json
    {
      "key": "session:token:x901",
      "ttl_updated": 600
    }
    ```
* **Response (Miss):**
  * **Status Code:** `404 Not Found`
  * **Response Body:** *(Standard error payload)*

---

### 1.6 TTL Check (Get remaining TTL)
* **HTTP Method:** `GET`
* **Path:** `/api/v1/cache/{key}/ttl`
* **Response (Active TTL):**
  * **Status Code:** `200 OK`
  * **Response Body:**
    ```json
    {
      "key": "session:token:x901",
      "ttl_remaining": 598
    }
    ```
* **Response (No Expiry):**
  * **Status Code:** `200 OK`
  * **Response Body:**
    ```json
    {
      "key": "session:token:x901",
      "ttl_remaining": -1
    }
    ```
* **Response (Miss):**
  * **Status Code:** `404 Not Found`

---

## 2. Phase 2 Cluster & Routing Endpoints

Phase 2 introduces a consistent hashing ring routing layer. The routing node (or clients) need visibility into health and routing mappings.

### 2.1 Node Health Check
* **HTTP Method:** `GET`
* **Path:** `/api/v1/cluster/health`
* **Response:**
  * **Status Code:** `200 OK`
  * **Response Body:**
    ```json
    {
      "nodeId": "Node-A",
      "status": "healthy",
      "activeKeys": 12450,
      "capacity": 20000,
      "uptime_seconds": 3600
    }
    ```

### 2.2 Get Routing Ring Map
* **HTTP Method:** `GET`
* **Path:** `/api/v1/cluster/ring`
* **Response:**
  * **Status Code:** `200 OK`
  * **Response Body:**
    ```json
    {
      "hashFunction": "Murmur3_32",
      "virtualNodesPerPhysicalNode": 150,
      "nodes": [
        {
          "nodeId": "Node-A",
          "address": "http://127.0.0.1:8081",
          "status": "healthy"
        },
        {
          "nodeId": "Node-B",
          "address": "http://127.0.0.1:8082",
          "status": "healthy"
        }
      ]
    }
    ```

---

## 3. Phase 3 Invalidation & Metrics Endpoints

### 3.1 Invalidation API (Wildcard / Flush)
* **HTTP Method:** `POST`
* **Path:** `/api/v1/cache/invalidate`
* **Request Body:**
  ```json
  {
    "pattern": "user:session:*"
  }
  ```
  *(Note: Use `*` to flush the entire cache.)*
* **Response:**
  * **Status Code:** `200 OK`
  * **Response Body:**
    ```json
    {
      "status": "success",
      "invalidatedKeysCount": 142
    }
    ```

### 3.2 System Metrics Endpoint (Spring Boot Actuator Integration)
* **HTTP Method:** `GET`
* **Path:** `/actuator/metrics/cairn.cache`
* **Response:**
  * **Status Code:** `200 OK`
  * **Response Body:**
    ```json
    {
      "name": "cairn.cache",
      "description": "Cairn engine cache operational statistics",
      "measurements": [
        { "statistic": "COUNT_KEYS", "value": 12450.0 },
        { "statistic": "HITS", "value": 98452.0 },
        { "statistic": "MISSES", "value": 15412.0 },
        { "statistic": "EVICTIONS_POLICY", "value": 451.0 },
        { "statistic": "EVICTIONS_TTL", "value": 1452.0 }
      ],
      "availableTags": [
        {
          "tag": "eviction.policy",
          "values": ["lru"]
        }
      ]
    }
    ```

### 3.3 Operations Latency Metrics
* **HTTP Method:** `GET`
* **Path:** `/actuator/metrics/cairn.cache.latency`
* **Response:**
  * **Status Code:** `200 OK`
  * **Response Body:**
    ```json
    {
      "name": "cairn.cache.latency",
      "measurements": [
        { "statistic": "MAX", "value": 5.42 },
        { "statistic": "P50", "value": 0.45 },
        { "statistic": "P95", "value": 1.25 },
        { "statistic": "P99", "value": 2.12 }
      ],
      "baseUnit": "milliseconds"
    }
    ```

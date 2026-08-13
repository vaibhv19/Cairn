# Data Model & Schema Document — Cairn (Distributed Cache Service)

## Document Control
* **Document Version:** 0.1.0
* **Status:** Draft
* **Authors:** Portfolio Owner / Technical Architect
* **Target Release:** v0.1.0 (Docs & MVP)
* **Twin Project Reference:** [Shard DB Schema]()

---

## 1. Database Non-Goals & In-Memory Scope

Cairn is built strictly as a transient, high-speed, in-memory cache. 

> [!IMPORTANT]
> **No Database / Persistence Layer:** Cairn does not connect to any SQL or NoSQL database (such as PostgreSQL, MySQL, or MongoDB), nor does it write backup files to local disk storage (like Redis RDB snapshots or Append-Only Files). All data is kept in volatile JVM heap memory. A restart of the JVM process clears the cache namespaces completely.

---

## 2. In-Memory Data Structures

Data is stored in-memory inside the `CacheEngine` utilizing Java structures. Below is the definition of the storage schema.

### 2.1 The Key-Value Map Registry
* **Collection:** `ConcurrentHashMap<String, CacheEntry>`
* **Primary Key:** `String` (The cache key)
* **Value Record:** `CacheEntry` (The wrapper encapsulating the payload and policy tracking metadata)

---

### 2.2 CacheEntry Structure
The `CacheEntry` class defines the structural "schema" of every cached record in memory.

```
+-------------------------------------------------------+
|                      CacheEntry                       |
+-------------------------------------------------------+
| - value: String (Payload)                             |
| - createdTime: long (Epoch ms)                        |
| - expiryTime: long (Epoch ms)                         |
| - lastAccessTime: long (Epoch ms - LRU metadata)      |
| - accessFrequency: int (Counter - LFU metadata)       |
+-------------------------------------------------------+
```

| Field Name | Java Data Type | Nullability | Purpose / Usage |
| :--- | :--- | :--- | :--- |
| **`value`** | `String` | Non-Null | The actual cached value payload. Serialized JSON format is recommended for complex objects. |
| **`createdTime`** | `long` | Non-Null | Epoch timestamp in milliseconds indicating when the key was written to the cache. |
| **`expiryTime`** | `long` | Non-Null | Epoch timestamp in milliseconds when the key is considered stale. If no TTL is set, this is configured to `Long.MAX_VALUE`. |
| **`lastAccessTime`**| `long` | Non-Null | Epoch timestamp in milliseconds when the key was last read via a `GET` command. Primary metadata driver for the **LRU Eviction Strategy**. |
| **`accessFrequency`**| `int` | Non-Null | A counter incremented on every key read. Primary metadata driver for the **LFU Eviction Strategy**. |

---

## 3. Eviction Metadata Structures

To perform $O(1)$ evictions without iterating over the entire `ConcurrentHashMap`, the swappable eviction policies maintain dedicated internal pointer index schemas.

### 3.1 LRU (Least Recently Used) Schema
The LRU eviction strategy tracks access recency using a custom doubly-linked list.

```
Head (Most Recent) <---> Node <---> Node <---> Tail (Least Recent / Victim)
```

* **Node Record Map:** `Map<String, LruNode>` (Holds quick pointers to the list nodes)
* **LruNode Structure:**
  * `key`: String (Referencing the cache key)
  * `prev`: LruNode pointer
  * `next`: LruNode pointer

---

### 3.2 LFU (Least Frequently Used) Schema
The LFU eviction strategy tracks access frequency using a frequency-bucket list.

```
Freq [1] Bucket <---> Freq [2] Bucket <---> Freq [N] Bucket
     |                     |                     |
  Keys [k1, k2]         Keys [k3]             Keys [k4, k5]
```

* **Node Map:** `Map<String, LfuNode>`
* **Frequency Table:** `Map<Integer, LinkedHashSet<String>>` (Maps access frequency to a linked set of keys, preserving LRU order within the same frequency bucket)
* **Min Frequency Index:** `int minFrequency` (Maintains a pointer to the lowest frequency list containing keys, ensuring $O(1)$ extraction of the eviction victim)

---

## 4. Open Questions / Future Considerations (Phase 3 Metrics)

During Phase 3, metrics dashboards aggregate hit/miss ratios and memory statistics.
* **Problem Statement:** If the cache server crashes or restarts, historical operational metrics are lost, preventing long-term performance comparisons between policy configurations.
* **Proposed Future Database Addition:** If persistent metrics are requested, the system will hook into an in-memory SQL database (such as **H2 Database Engine** or **SQLite**) to log metrics snapshots:
  
  ```sql
  CREATE TABLE cache_metrics_snapshot (
      id BIGINT AUTO_INCREMENT PRIMARY KEY,
      snapshot_timestamp TIMESTAMP NOT NULL,
      node_identifier VARCHAR(100) NOT NULL,
      policy_type VARCHAR(10) NOT NULL, -- LRU or LFU
      total_keys INT NOT NULL,
      hit_ratio DOUBLE NOT NULL,
      total_evictions INT NOT NULL,
      memory_bytes BIGINT NOT NULL
  );
  ```
* **Status:** This persistence layer remains **unimplemented** and serves only as a design reference for Phase 3 observability extensions.

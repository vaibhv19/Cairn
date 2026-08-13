# App Flow Document — Cairn (Distributed Cache Service)

## Document Control
* **Document Version:** 0.1.0
* **Status:** Draft
* **Authors:** Portfolio Owner / Technical Architect
* **Target Release:** v0.1.0 (Docs & MVP)
* **Twin Project Reference:** [Shard App Flow]()

---

## 1. MVP Single-Node Core Request Lifecycles

All client requests enter via Spring Boot REST controllers. Below are the sequential steps executed inside the JVM container for each core cache operation.

### 1.1 GET Request Flow
1. **HTTP Ingest:** The client calls `GET /api/v1/cache/{key}`. Spring Web allocates a Tomcat thread (or Virtual Thread) to handle the request.
2. **Registry Lookup:** The engine queries `ConcurrentHashMap.get(key)` in a lock-free manner.
3. **Existence Check:**
   * **If Key Absent:** The engine immediately increments the `misses` counter and returns `404 Not Found`.
   * **If Key Present:** The engine extracts the `CacheEntry` record containing the payload and metadata (expiry timestamp, frequency counter, access timestamp).
4. **TTL Expiration Check (Passive Expiry):**
   * The thread compares `System.currentTimeMillis()` against the entry's `expiryTime`.
   * **If Expired:**
     * The thread acquires the cache's structural `WriteLock`.
     * It removes the entry from the `ConcurrentHashMap`.
     * It removes the key metadata from the eviction strategy bean (e.g., deletes the node from the LRU list).
     * It releases the `WriteLock`, increments the `misses` and `ttl_evictions` counters, and returns `404 Not Found`.
5. **Eviction Metadata Update (Promotion):**
   * **If Valid (Not Expired):** The thread acquires the eviction strategy `ReadLock`.
   * It updates the key's access tracking stats:
     * **LRU:** Moves the node representing the key to the head of the doubly-linked list.
     * **LFU:** Increments the reference frequency count and moves the key to its corresponding frequency bucket.
   * The thread releases the `ReadLock`.
6. **HTTP Response:** The engine increments the `hits` counter and returns the value with `200 OK`.

---

### 1.2 SET Request Flow
1. **HTTP Ingest:** Client calls `POST /api/v1/cache` with `{ "key": "k", "value": "v", "ttl": 60 }`.
2. **Lookup & Update:** The engine checks if the key already exists in the `ConcurrentHashMap`.
   * **If Existing:**
     * The thread locks the target hash bucket inside `ConcurrentHashMap` via segment-level hashing.
     * It overwrites the value, updates the expiration time (`System.currentTimeMillis() + ttl`), and resets/updates access statistics in the eviction Strategy bean.
     * The thread releases the bucket lock, increments write metrics, and returns `200 OK`.
   * **If New:**
     * The thread proceeds to the **Capacity Check**.
3. **Capacity Check & Eviction:**
   * The engine checks if `ConcurrentHashMap.size() >= maxCapacity`.
   * **If Over Capacity:**
     * The thread acquires the eviction strategy structural `WriteLock`.
     * It requests the eviction strategy bean (LRU/LFU) to select the victim key (the tail of the LRU list, or the head of the lowest LFU frequency list).
     * The engine removes the selected victim key from the `ConcurrentHashMap` and the eviction list.
     * The thread releases the `WriteLock` and increments the `policy_evictions` counter.
4. **Insert Entry:**
   * The thread inserts the new `CacheEntry` into the `ConcurrentHashMap`.
   * It acquires the eviction strategy structural `WriteLock` and inserts the key node into the eviction strategy (head of LRU list, or frequency 1 bucket of LFU).
   * It releases the `WriteLock`.
5. **HTTP Response:** Returns `201 Created` with the inserted key metadata.

---

### 1.3 DELETE Request Flow
1. **HTTP Ingest:** Client calls `DELETE /api/v1/cache/{key}`.
2. **Engine Execution:**
   * The thread locks the bucket in `ConcurrentHashMap` and removes the key.
   * **If Key Not Found:** Returns `404 Not Found`.
   * **If Key Found:**
     * The thread acquires the eviction strategy `WriteLock`.
     * It removes the key's metadata node from the LRU/LFU list.
     * It releases the `WriteLock` and deletes the active TTL configurations.
3. **HTTP Response:** Returns `204 No Content`.

---

### 1.4 EXISTS / EXPIRE / TTL Request Flows
* **EXISTS (`GET /api/v1/cache/{key}/exists`):**
  1. Executes the GET passive-expiry check.
  2. If valid, returns `200 OK` with `{ "exists": true }`. Does *not* trigger LRU/LFU promotions, ensuring audits do not poison access patterns.
* **EXPIRE (`POST /api/v1/cache/{key}/expire`):**
  1. Performs a lookup in `ConcurrentHashMap`.
  2. If present, updates the expiration timestamp (`currentTime + requested_ttl`).
  3. Returns `200 OK`. If absent, returns `404 Not Found`.
* **TTL (`GET /api/v1/cache/{key}/ttl`):**
  1. Runs the passive-expiry check.
  2. If valid, subtracts the current timestamp from the expiry timestamp and returns `{ "ttl": remaining_seconds }` with `200 OK`.

---

## 2. Concurrency Contention Paths

When multiple client threads access the same cache keys in parallel, Cairn manages conflicts through thread barriers and lock hierarchies.

### 2.1 Read/Write Contention (Same Key)
```
Thread 1 (SET Key "User1")                  Thread 2 (GET Key "User1")
       |                                           |
       v                                           v
Lock CHM Bucket for "User1"                Try CHM lock-free read
Writes new value                           Reads volatile entry reference
Acquire Eviction WRITE Lock                Acquire Eviction READ Lock
- (Blocks Thread 2)                        - (Waiting for WRITE lock release)
Updates LRU list                           - 
Release Eviction WRITE Lock                - 
Release CHM Bucket Lock                    -
       |                                   Unlock WRITE lock -> Read Lock Acquired
       v                                   Promotes node in LRU list
(Write Complete)                           Release Eviction READ Lock
                                           Return value
```

### 2.2 Active Background Expiry Sweep Flow
The `ScheduledExecutorService` sweep thread runs concurrently with incoming HTTP requests:
1. **Trigger:** The background scheduler fires the sweep thread (e.g., every 5,000ms).
2. **Sampling:** The thread locks the key set reference (or accesses it via a weakly-consistent iterator) to sample a batch of 20 keys.
3. **Inspection:** For each key in the sample:
   * It checks the expiration timestamp.
   * **If Valid:** Skips the key.
   * **If Expired:**
     * Acquires the structural eviction `WriteLock`.
     * Removes the key from the `ConcurrentHashMap`.
     * Removes the key from the LRU/LFU strategy trackers.
     * Releases the structural eviction `WriteLock`.
     * Increments the `ttl_evictions` metrics counter.
4. **Adaptive Loop:** If more than $25\%$ (5 keys) of the sample were expired, it immediately loops back to step 2 to process another sample, protecting memory from sudden expiry floods. If less than $25\%$, the thread goes back to sleep.

---

## 3. Phase 2 Distributed Hashing Flows

### 3.1 Distributed Key Routing Flow
```
Client Request -> [Routing Proxy] 
                       |
                       v
            Hash Key (Murmur3) -> Hash Value H
                       |
                       v
         TreeMap.tailMap(H) Ring Search
                       |
                       v
         Retrieve target Physical Node IP
                       |
                       v
             Proxy Request to Target
```

### 3.2 Dynamic Static-Node Modification (Rebalancing Flow)
When the static node layout configuration in `application.yml` is updated:
1. **Initialization:** The Proxy reload command is triggered.
2. **Ring Recalculation:** The routing layer clears the current `TreeMap` ring, reads the new list of nodes, hashes the new virtual nodes, and populates the ring.
3. **Request Distribution Change:**
   * Future GET/SET requests use the new ring mappings.
   * Since consistent hashing is used, any request for a key whose node location did not change continues to hit its previous cache node (maintaining a cache hit).
   * Requests mapping to a new node address return a cache miss, prompting client-side writebacks that naturally migrate key ownership without database dumps.

---

## 4. Phase 3 Invalidation & Metrics Flows

### 4.1 Write-Through vs. Write-Back Operations
* **Write-Through Flow:**
  1. Client sends write command.
  2. Cache thread writes to local `ConcurrentHashMap`.
  3. Cache thread synchronously makes an HTTP write call to the mock database.
  4. Returns `201 Created` to the client only after both storage targets confirm writing.
* **Write-Back (Write-Behind) Flow:**
  1. Client sends write command.
  2. Cache thread writes to local `ConcurrentHashMap`.
  3. Cache thread appends the write event to an internal thread-safe queue (`LinkedBlockingQueue`).
  4. Returns `201 Created` immediately to the client.
  5. A background worker thread drains the queue and pushes updates to the mock database asynchronously.

### 4.2 Metrics Collection Pipeline
1. An event occurs in the cache engine (e.g., Cache Hit).
2. The engine calls `metricsCollector.recordHit()`.
3. The metrics collector retrieves the local thread cell inside a `LongAdder`.
4. The cell value increments lock-free.
5. Every 10 seconds, Spring Boot Actuator scrapes the `LongAdder` sums and publishes them to `/actuator/metrics`, maintaining zero performance tax on the request path.

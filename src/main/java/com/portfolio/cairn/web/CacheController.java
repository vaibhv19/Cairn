package com.portfolio.cairn.web;

import com.portfolio.cairn.engine.CacheEngine;
import com.portfolio.cairn.engine.CacheEntry;
import com.portfolio.cairn.exception.KeyNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cache")
public class CacheController {

    private final CacheEngine cacheEngine;

    @Autowired
    public CacheController(CacheEngine cacheEngine) {
        this.cacheEngine = cacheEngine;
    }

    @PostMapping
    public ResponseEntity<CacheDtos.SetResponse> set(@RequestBody CacheDtos.SetRequest request) {
        cacheEngine.set(request.key(), request.value(), request.ttl());
        return ResponseEntity.ok(new CacheDtos.SetResponse(request.key(), "cached", request.ttl()));
    }

    @GetMapping("/{key}")
    public ResponseEntity<CacheDtos.GetResponse> get(@PathVariable String key) {
        CacheEntry entry = cacheEngine.get(key);
        if (entry == null) {
            throw new KeyNotFoundException("Requested key does not exist or has expired.");
        }
        long ttlRemaining = entry.expiryTime() == Long.MAX_VALUE ? -1L :
                Math.max(0, (entry.expiryTime() - System.currentTimeMillis()) / 1000);
        return ResponseEntity.ok(new CacheDtos.GetResponse(key, entry.value(), ttlRemaining));
    }

    @DeleteMapping("/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String key) {
        CacheEntry deleted = cacheEngine.delete(key);
        if (deleted == null) {
            throw new KeyNotFoundException("Cannot delete key: key does not exist.");
        }
    }

    @GetMapping("/{key}/exists")
    public ResponseEntity<CacheDtos.ExistsResponse> exists(@PathVariable String key) {
        boolean exists = cacheEngine.exists(key);
        return ResponseEntity.ok(new CacheDtos.ExistsResponse(key, exists));
    }

    @PostMapping("/{key}/expire")
    public ResponseEntity<CacheDtos.ExpireResponse> expire(
            @PathVariable String key,
            @RequestBody CacheDtos.ExpireRequest request
    ) {
        CacheEntry entry = cacheEngine.get(key);
        if (entry == null) {
            throw new KeyNotFoundException("Requested key does not exist or has expired.");
        }
        cacheEngine.set(key, entry.value(), request.ttl());
        return ResponseEntity.ok(new CacheDtos.ExpireResponse(key, request.ttl()));
    }

    @GetMapping("/{key}/ttl")
    public ResponseEntity<CacheDtos.TtlResponse> ttl(@PathVariable String key) {
        CacheEntry entry = cacheEngine.get(key);
        if (entry == null) {
            throw new KeyNotFoundException("Requested key does not exist or has expired.");
        }
        long ttlRemaining = entry.expiryTime() == Long.MAX_VALUE ? -1L :
                Math.max(0, (entry.expiryTime() - System.currentTimeMillis()) / 1000);
        return ResponseEntity.ok(new CacheDtos.TtlResponse(key, ttlRemaining));
    }
}

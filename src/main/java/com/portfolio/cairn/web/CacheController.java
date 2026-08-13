package com.portfolio.cairn.web;

import com.portfolio.cairn.sharding.NodeRouter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cache")
public class CacheController {

    private final NodeRouter nodeRouter;

    @Autowired
    public CacheController(NodeRouter nodeRouter) {
        this.nodeRouter = nodeRouter;
    }

    @PostMapping
    public ResponseEntity<?> set(@RequestBody CacheDtos.SetRequest request) {
        return nodeRouter.set(request);
    }

    @GetMapping("/{key}")
    public ResponseEntity<?> get(@PathVariable String key) {
        return nodeRouter.get(key);
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<?> delete(@PathVariable String key) {
        return nodeRouter.delete(key);
    }

    @GetMapping("/{key}/exists")
    public ResponseEntity<?> exists(@PathVariable String key) {
        return nodeRouter.exists(key);
    }

    @PostMapping("/{key}/expire")
    public ResponseEntity<?> expire(
            @PathVariable String key,
            @RequestBody CacheDtos.ExpireRequest request
    ) {
        return nodeRouter.expire(key, request);
    }

    @GetMapping("/{key}/ttl")
    public ResponseEntity<?> ttl(@PathVariable String key) {
        return nodeRouter.ttl(key);
    }

    @PostMapping("/invalidate")
    public ResponseEntity<?> invalidate(
            @RequestBody CacheDtos.InvalidateRequest request,
            @RequestParam(value = "localOnly", required = false, defaultValue = "false") boolean localOnly
    ) {
        return nodeRouter.invalidate(request, localOnly);
    }
}

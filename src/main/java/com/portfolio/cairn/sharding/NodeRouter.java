package com.portfolio.cairn.sharding;

import com.portfolio.cairn.engine.CacheEngine;
import com.portfolio.cairn.engine.CacheEntry;
import com.portfolio.cairn.exception.KeyNotFoundException;
import com.portfolio.cairn.engine.InvalidationService;
import com.portfolio.cairn.web.CacheDtos;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

/**
 * Proxy routing layer. Uses WebClient with connection pooling to forward
 * CRUD cache operations to target nodes if the key hashes to a remote node.
 */
@Service
public class NodeRouter {

    private final ConsistentHashRing consistentHashRing;
    private final CacheEngine cacheEngine;
    private final NodeConfig nodeConfig;
    private final WebClient webClient;
    private final InvalidationService invalidationService;

    @Autowired
    public NodeRouter(
            ConsistentHashRing consistentHashRing,
            CacheEngine cacheEngine,
            NodeConfig nodeConfig,
            WebClient.Builder webClientBuilder,
            InvalidationService invalidationService
    ) {
        this.consistentHashRing = consistentHashRing;
        this.cacheEngine = cacheEngine;
        this.nodeConfig = nodeConfig;
        this.invalidationService = invalidationService;

        // Configure resilient connection pool for Netty HttpClient
        ConnectionProvider provider = ConnectionProvider.builder("cairn-router-pool")
                .maxConnections(500)
                .pendingAcquireTimeout(Duration.ofSeconds(45))
                .maxIdleTime(Duration.ofSeconds(10))
                .build();

        HttpClient httpClient = HttpClient.create(provider);
        this.webClient = webClientBuilder
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    private String getAddressForNode(String nodeId) {
        return nodeConfig.getNodes().stream()
                .filter(n -> n.getNodeId().equals(nodeId))
                .map(NodeConfig.ClusterNode::getAddress)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown node ID: " + nodeId));
    }

    /**
     * Executes the WebClient call synchronously, preserving the status code, content type,
     * and body verbatim.
     */
    private ResponseEntity<String> forwardRequest(WebClient.RequestHeadersSpec<?> requestSpec) {
        try {
            return requestSpec.retrieve()
                    .toEntity(String.class)
                    .block();
        } catch (WebClientResponseException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .contentType(e.getHeaders().getContentType())
                    .body(e.getResponseBodyAsString());
        }
    }

    public ResponseEntity<?> set(CacheDtos.SetRequest request) {
        String nodeId = consistentHashRing.getNode(request.key());
        if (nodeId == null) {
            throw new IllegalStateException("Consistent Hash Ring is empty!");
        }
        if (nodeId.equals(nodeConfig.getLocalNodeId())) {
            cacheEngine.set(request.key(), request.value(), request.ttl());
            return ResponseEntity.ok(new CacheDtos.SetResponse(request.key(), "cached", request.ttl()));
        } else {
            String address = getAddressForNode(nodeId);
            return forwardRequest(webClient.post()
                    .uri(address + "/api/v1/cache")
                    .bodyValue(request));
        }
    }

    public ResponseEntity<?> get(String key) {
        String nodeId = consistentHashRing.getNode(key);
        if (nodeId == null) {
            throw new IllegalStateException("Consistent Hash Ring is empty!");
        }
        if (nodeId.equals(nodeConfig.getLocalNodeId())) {
            CacheEntry entry = cacheEngine.get(key);
            if (entry == null) {
                throw new KeyNotFoundException("Requested key does not exist or has expired.");
            }
            long ttlRemaining = entry.expiryTime() == Long.MAX_VALUE ? -1L :
                    Math.max(0, (entry.expiryTime() - System.currentTimeMillis()) / 1000);
            return ResponseEntity.ok(new CacheDtos.GetResponse(key, entry.value(), ttlRemaining));
        } else {
            String address = getAddressForNode(nodeId);
            return forwardRequest(webClient.get().uri(address + "/api/v1/cache/" + key));
        }
    }

    public ResponseEntity<?> delete(String key) {
        String nodeId = consistentHashRing.getNode(key);
        if (nodeId == null) {
            throw new IllegalStateException("Consistent Hash Ring is empty!");
        }
        if (nodeId.equals(nodeConfig.getLocalNodeId())) {
            CacheEntry deleted = cacheEngine.delete(key);
            if (deleted == null) {
                throw new KeyNotFoundException("Cannot delete key: key does not exist.");
            }
            return ResponseEntity.noContent().build();
        } else {
            String address = getAddressForNode(nodeId);
            return forwardRequest(webClient.delete().uri(address + "/api/v1/cache/" + key));
        }
    }

    public ResponseEntity<?> exists(String key) {
        String nodeId = consistentHashRing.getNode(key);
        if (nodeId == null) {
            throw new IllegalStateException("Consistent Hash Ring is empty!");
        }
        if (nodeId.equals(nodeConfig.getLocalNodeId())) {
            boolean exists = cacheEngine.exists(key);
            return ResponseEntity.ok(new CacheDtos.ExistsResponse(key, exists));
        } else {
            String address = getAddressForNode(nodeId);
            return forwardRequest(webClient.get().uri(address + "/api/v1/cache/" + key + "/exists"));
        }
    }

    public ResponseEntity<?> expire(String key, CacheDtos.ExpireRequest request) {
        String nodeId = consistentHashRing.getNode(key);
        if (nodeId == null) {
            throw new IllegalStateException("Consistent Hash Ring is empty!");
        }
        if (nodeId.equals(nodeConfig.getLocalNodeId())) {
            CacheEntry entry = cacheEngine.get(key);
            if (entry == null) {
                throw new KeyNotFoundException("Requested key does not exist or has expired.");
            }
            cacheEngine.set(key, entry.value(), request.ttl());
            return ResponseEntity.ok(new CacheDtos.ExpireResponse(key, request.ttl()));
        } else {
            String address = getAddressForNode(nodeId);
            return forwardRequest(webClient.post()
                    .uri(address + "/api/v1/cache/" + key + "/expire")
                    .bodyValue(request));
        }
    }

    public ResponseEntity<?> ttl(String key) {
        String nodeId = consistentHashRing.getNode(key);
        if (nodeId == null) {
            throw new IllegalStateException("Consistent Hash Ring is empty!");
        }
        if (nodeId.equals(nodeConfig.getLocalNodeId())) {
            CacheEntry entry = cacheEngine.get(key);
            if (entry == null) {
                throw new KeyNotFoundException("Requested key does not exist or has expired.");
            }
            long ttlRemaining = entry.expiryTime() == Long.MAX_VALUE ? -1L :
                    Math.max(0, (entry.expiryTime() - System.currentTimeMillis()) / 1000);
            return ResponseEntity.ok(new CacheDtos.TtlResponse(key, ttlRemaining));
        } else {
            String address = getAddressForNode(nodeId);
            return forwardRequest(webClient.get().uri(address + "/api/v1/cache/" + key + "/ttl"));
        }
    }

    public ResponseEntity<?> invalidate(CacheDtos.InvalidateRequest request, boolean localOnly) {
        if (localOnly) {
            int count = invalidationService.invalidateByPattern(request.pattern());
            return ResponseEntity.ok(new CacheDtos.InvalidateResponse("success", count));
        }

        if (request.pattern().contains("*")) {
            int totalCount = 0;
            for (NodeConfig.ClusterNode node : nodeConfig.getNodes()) {
                if (node.getNodeId().equals(nodeConfig.getLocalNodeId())) {
                    totalCount += invalidationService.invalidateByPattern(request.pattern());
                } else {
                    try {
                        CacheDtos.InvalidateResponse response = webClient.post()
                                .uri(node.getAddress() + "/api/v1/cache/invalidate?localOnly=true")
                                .bodyValue(request)
                                .retrieve()
                                .bodyToMono(CacheDtos.InvalidateResponse.class)
                                .block();
                        if (response != null) {
                            totalCount += response.invalidatedKeysCount();
                        }
                    } catch (Exception e) {
                        // ignore or handle remote node connection issues
                    }
                }
            }
            return ResponseEntity.ok(new CacheDtos.InvalidateResponse("success", totalCount));
        } else {
            String nodeId = consistentHashRing.getNode(request.pattern());
            if (nodeId == null) {
                throw new IllegalStateException("Consistent Hash Ring is empty!");
            }
            if (nodeId.equals(nodeConfig.getLocalNodeId())) {
                int count = invalidationService.invalidateByPattern(request.pattern());
                return ResponseEntity.ok(new CacheDtos.InvalidateResponse("success", count));
            } else {
                String address = getAddressForNode(nodeId);
                return forwardRequest(webClient.post()
                        .uri(address + "/api/v1/cache/invalidate?localOnly=true")
                        .bodyValue(request));
            }
        }
    }
}

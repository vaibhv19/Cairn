package com.portfolio.cairn.web;

import com.portfolio.cairn.engine.CacheEngine;
import com.portfolio.cairn.sharding.NodeConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Controller exposing REST API endpoints to monitor the cluster topology
 * and the health status of individual nodes.
 */
@RestController
@RequestMapping("/api/v1/cluster")
public class RoutingController {

    private final NodeConfig nodeConfig;
    private final CacheEngine cacheEngine;
    private final long startTime;

    @Autowired
    public RoutingController(NodeConfig nodeConfig, CacheEngine cacheEngine) {
        this.nodeConfig = nodeConfig;
        this.cacheEngine = cacheEngine;
        this.startTime = System.currentTimeMillis();
    }

    /**
     * GET /api/v1/cluster/health
     * Returns the health status, active keys, capacity, and uptime of the local node.
     */
    @GetMapping("/health")
    public ResponseEntity<ClusterDtos.HealthResponse> getHealth() {
        long uptimeSeconds = (System.currentTimeMillis() - startTime) / 1000;
        ClusterDtos.HealthResponse response = new ClusterDtos.HealthResponse(
                nodeConfig.getLocalNodeId(),
                "healthy",
                cacheEngine.size(),
                cacheEngine.getMaxCapacity(),
                uptimeSeconds
        );
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/cluster/ring
     * Returns the routing ring structure parameters and list of nodes.
     */
    @GetMapping("/ring")
    public ResponseEntity<ClusterDtos.RingResponse> getRing() {
        int vnodes = nodeConfig.getNodes().isEmpty() ? 150 : nodeConfig.getNodes().get(0).getVirtualNodes();
        
        List<ClusterDtos.ClusterNodeInfo> nodesList = nodeConfig.getNodes().stream()
                .map(node -> new ClusterDtos.ClusterNodeInfo(node.getNodeId(), node.getAddress(), "healthy"))
                .collect(Collectors.toList());

        ClusterDtos.RingResponse response = new ClusterDtos.RingResponse(
                "Murmur3_32",
                vnodes,
                nodesList
        );
        return ResponseEntity.ok(response);
    }
}

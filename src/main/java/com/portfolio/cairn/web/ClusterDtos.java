package com.portfolio.cairn.web;

import java.util.List;

/**
 * Data Transfer Objects for Phase 2 cluster routing and health status monitoring endpoints.
 */
public class ClusterDtos {

    public record HealthResponse(
            String nodeId,
            String status,
            int activeKeys,
            int capacity,
            long uptime_seconds
    ) {}

    public record RingResponse(
            String hashFunction,
            int virtualNodesPerPhysicalNode,
            List<ClusterNodeInfo> nodes
    ) {}

    public record ClusterNodeInfo(
            String nodeId,
            String address,
            String status
    ) {}
}

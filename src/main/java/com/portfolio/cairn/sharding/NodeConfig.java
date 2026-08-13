package com.portfolio.cairn.sharding;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses and validates the static list of cluster node addresses and virtual node counts
 * from application.yml under 'cairn.cluster'. Populates the ConsistentHashRing at startup.
 */
@Configuration
@ConfigurationProperties(prefix = "cairn.cluster")
public class NodeConfig {

    private String localNodeId;
    private List<ClusterNode> nodes = new ArrayList<>();

    @Autowired
    private ConsistentHashRing consistentHashRing;

    public String getLocalNodeId() {
        return localNodeId;
    }

    public void setLocalNodeId(String localNodeId) {
        this.localNodeId = localNodeId;
    }

    public List<ClusterNode> getNodes() {
        return nodes;
    }

    public void setNodes(List<ClusterNode> nodes) {
        this.nodes = nodes;
    }

    @PostConstruct
    public void init() {
        // Validate configuration
        if (localNodeId == null || localNodeId.trim().isEmpty()) {
            throw new IllegalArgumentException("Local node ID (cairn.cluster.local-node-id) must be configured.");
        }
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("Cluster nodes list (cairn.cluster.nodes) must be configured and not empty.");
        }

        boolean localNodeFound = false;
        for (ClusterNode node : nodes) {
            if (node.getNodeId() == null || node.getNodeId().trim().isEmpty()) {
                throw new IllegalArgumentException("Cluster node ID must not be empty.");
            }
            if (node.getAddress() == null || node.getAddress().trim().isEmpty()) {
                throw new IllegalArgumentException("Cluster node address must not be empty for node: " + node.getNodeId());
            }
            if (node.getVirtualNodes() == null || node.getVirtualNodes() <= 0) {
                throw new IllegalArgumentException("Virtual node count must be a positive integer for node: " + node.getNodeId());
            }
            if (node.getNodeId().equals(localNodeId)) {
                localNodeFound = true;
            }
        }

        if (!localNodeFound) {
            throw new IllegalArgumentException("Local node ID '" + localNodeId + "' must be present in the cluster nodes configuration list.");
        }

        // Initialize Consistent Hash Ring
        consistentHashRing.clear();
        for (ClusterNode node : nodes) {
            consistentHashRing.addNode(node.getNodeId(), node.getVirtualNodes());
        }
    }

    public static class ClusterNode {
        private String nodeId;
        private String address;
        private Integer virtualNodes = 150; // default 150

        public String getNodeId() {
            return nodeId;
        }

        public void setNodeId(String nodeId) {
            this.nodeId = nodeId;
        }

        public String getAddress() {
            return address;
        }

        public void setAddress(String address) {
            this.address = address;
        }

        public Integer getVirtualNodes() {
            return virtualNodes;
        }

        public void setVirtualNodes(Integer virtualNodes) {
            this.virtualNodes = virtualNodes;
        }
    }
}

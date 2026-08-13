package com.portfolio.cairn.unit;

import com.portfolio.cairn.sharding.ConsistentHashRing;
import com.portfolio.cairn.sharding.NodeConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

public class NodeConfigTest {

    private ConsistentHashRing mockRing;
    private NodeConfig nodeConfig;

    @BeforeEach
    public void setUp() {
        mockRing = mock(ConsistentHashRing.class);
        nodeConfig = new NodeConfig();
        ReflectionTestUtils.setField(nodeConfig, "consistentHashRing", mockRing);
    }

    @Test
    public void testValidConfigInitialization() {
        nodeConfig.setLocalNodeId("Node-A");
        
        List<NodeConfig.ClusterNode> nodes = new ArrayList<>();
        NodeConfig.ClusterNode n1 = new NodeConfig.ClusterNode();
        n1.setNodeId("Node-A");
        n1.setAddress("http://localhost:8081");
        n1.setVirtualNodes(150);
        nodes.add(n1);
        
        NodeConfig.ClusterNode n2 = new NodeConfig.ClusterNode();
        n2.setNodeId("Node-B");
        n2.setAddress("http://localhost:8082");
        n2.setVirtualNodes(150);
        nodes.add(n2);
        
        nodeConfig.setNodes(nodes);

        // When
        nodeConfig.init();

        // Then
        verify(mockRing).clear();
        verify(mockRing).addNode("Node-A", 150);
        verify(mockRing).addNode("Node-B", 150);
    }

    @Test
    public void testMissingLocalNodeId() {
        nodeConfig.setLocalNodeId(null);
        assertThatThrownBy(() -> nodeConfig.init())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Local node ID");
    }

    @Test
    public void testEmptyNodesList() {
        nodeConfig.setLocalNodeId("Node-A");
        nodeConfig.setNodes(new ArrayList<>());
        assertThatThrownBy(() -> nodeConfig.init())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nodes list");
    }

    @Test
    public void testMissingAddress() {
        nodeConfig.setLocalNodeId("Node-A");
        List<NodeConfig.ClusterNode> nodes = new ArrayList<>();
        NodeConfig.ClusterNode n1 = new NodeConfig.ClusterNode();
        n1.setNodeId("Node-A");
        n1.setAddress(null);
        nodes.add(n1);
        nodeConfig.setNodes(nodes);

        assertThatThrownBy(() -> nodeConfig.init())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("address must not be empty");
    }

    @Test
    public void testInvalidVirtualNodes() {
        nodeConfig.setLocalNodeId("Node-A");
        List<NodeConfig.ClusterNode> nodes = new ArrayList<>();
        NodeConfig.ClusterNode n1 = new NodeConfig.ClusterNode();
        n1.setNodeId("Node-A");
        n1.setAddress("http://localhost:8081");
        n1.setVirtualNodes(0); // invalid
        nodes.add(n1);
        nodeConfig.setNodes(nodes);

        assertThatThrownBy(() -> nodeConfig.init())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Virtual node count must be a positive integer");
    }

    @Test
    public void testLocalNodeIdNotPresentInNodes() {
        nodeConfig.setLocalNodeId("Node-C"); // Node-C is not in the list
        List<NodeConfig.ClusterNode> nodes = new ArrayList<>();
        NodeConfig.ClusterNode n1 = new NodeConfig.ClusterNode();
        n1.setNodeId("Node-A");
        n1.setAddress("http://localhost:8081");
        n1.setVirtualNodes(150);
        nodes.add(n1);
        nodeConfig.setNodes(nodes);

        assertThatThrownBy(() -> nodeConfig.init())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be present in the cluster nodes configuration list");
    }
}

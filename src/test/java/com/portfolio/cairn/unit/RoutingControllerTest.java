package com.portfolio.cairn.unit;

import com.portfolio.cairn.engine.CacheEngine;
import com.portfolio.cairn.sharding.ConsistentHashRing;
import com.portfolio.cairn.sharding.NodeConfig;
import com.portfolio.cairn.web.RoutingController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RoutingController.class)
public class RoutingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private NodeConfig nodeConfig;

    @MockBean
    private CacheEngine cacheEngine;

    @MockBean
    private ConsistentHashRing consistentHashRing; // Mocked because it is in context

    @BeforeEach
    public void setUp() {
        when(nodeConfig.getLocalNodeId()).thenReturn("Node-A");
        when(cacheEngine.size()).thenReturn(100);
        when(cacheEngine.getMaxCapacity()).thenReturn(1000);

        List<NodeConfig.ClusterNode> nodes = new ArrayList<>();
        NodeConfig.ClusterNode n1 = new NodeConfig.ClusterNode();
        n1.setNodeId("Node-A");
        n1.setAddress("http://127.0.0.1:8081");
        n1.setVirtualNodes(150);
        nodes.add(n1);

        NodeConfig.ClusterNode n2 = new NodeConfig.ClusterNode();
        n2.setNodeId("Node-B");
        n2.setAddress("http://127.0.0.1:8082");
        n2.setVirtualNodes(150);
        nodes.add(n2);

        when(nodeConfig.getNodes()).thenReturn(nodes);
    }

    @Test
    public void testGetHealthEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/cluster/health")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeId").value("Node-A"))
                .andExpect(jsonPath("$.status").value("healthy"))
                .andExpect(jsonPath("$.activeKeys").value(100))
                .andExpect(jsonPath("$.capacity").value(1000))
                .andExpect(jsonPath("$.uptime_seconds").isNumber());
    }

    @Test
    public void testGetRingEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/cluster/ring")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hashFunction").value("Murmur3_32"))
                .andExpect(jsonPath("$.virtualNodesPerPhysicalNode").value(150))
                .andExpect(jsonPath("$.nodes[0].nodeId").value("Node-A"))
                .andExpect(jsonPath("$.nodes[0].address").value("http://127.0.0.1:8081"))
                .andExpect(jsonPath("$.nodes[0].status").value("healthy"))
                .andExpect(jsonPath("$.nodes[1].nodeId").value("Node-B"))
                .andExpect(jsonPath("$.nodes[1].address").value("http://127.0.0.1:8082"))
                .andExpect(jsonPath("$.nodes[1].status").value("healthy"));
    }
}

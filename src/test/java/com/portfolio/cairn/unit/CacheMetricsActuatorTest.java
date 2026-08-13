package com.portfolio.cairn.unit;

import com.portfolio.cairn.engine.CacheEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test verifying that custom Cairn cache metrics are successfully bound
 * to the Spring Boot Actuator endpoint namespace and serialise to the expected formats.
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc
public class CacheMetricsActuatorTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CacheEngine cacheEngine;

    @BeforeEach
    public void setUp() {
        // Trigger some cache activity to ensure metrics are populated
        cacheEngine.get("actuator-key-test"); // Miss
        cacheEngine.set("actuator-key-test", "val");
        cacheEngine.get("actuator-key-test"); // Hit
    }

    @Test
    public void testCairnCacheMetricsEndpoint() throws Exception {
        mockMvc.perform(get("/actuator/metrics/cairn.cache")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("cairn.cache"))
                .andExpect(jsonPath("$.measurements").isArray())
                .andExpect(jsonPath("$.availableTags").isArray());
    }

    @Test
    public void testCairnCacheLatencyMetricsEndpoint() throws Exception {
        mockMvc.perform(get("/actuator/metrics/cairn.cache.latency")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("cairn.cache.latency"))
                .andExpect(jsonPath("$.measurements").isArray());
    }
}

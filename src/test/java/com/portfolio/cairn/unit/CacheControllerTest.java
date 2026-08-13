package com.portfolio.cairn.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.cairn.engine.CacheEngine;
import com.portfolio.cairn.engine.CacheEntry;
import com.portfolio.cairn.exception.EvictionFailedException;
import com.portfolio.cairn.exception.InvalidTtlException;
import com.portfolio.cairn.web.CacheController;
import com.portfolio.cairn.web.CacheDtos;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CacheController.class)
public class CacheControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CacheEngine cacheEngine;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    public void testSetSuccess() throws Exception {
        CacheDtos.SetRequest request = new CacheDtos.SetRequest("k1", "v1", 300L);

        mockMvc.perform(post("/api/v1/cache")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key", is("k1")))
                .andExpect(jsonPath("$.status", is("cached")))
                .andExpect(jsonPath("$.ttl", is(300)));
    }

    @Test
    public void testSetInvalidTtl() throws Exception {
        CacheDtos.SetRequest request = new CacheDtos.SetRequest("k1", "v1", -10L);

        Mockito.doThrow(new InvalidTtlException("TTL must be a positive integer."))
                .when(cacheEngine).set(eq("k1"), eq("v1"), eq(-10L));

        mockMvc.perform(post("/api/v1/cache")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is("error")))
                .andExpect(jsonPath("$.errorCode", is("INVALID_TTL")))
                .andExpect(jsonPath("$.message", is("TTL must be a positive integer.")))
                .andExpect(jsonPath("$.timestamp", notNullValue()));
    }

    @Test
    public void testSetEvictionFailed() throws Exception {
        CacheDtos.SetRequest request = new CacheDtos.SetRequest("k1", "v1", null);

        Mockito.doThrow(new EvictionFailedException("Cache capacity reached and eviction was unable to free memory."))
                .when(cacheEngine).set(eq("k1"), eq("v1"), isNull());

        mockMvc.perform(post("/api/v1/cache")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInsufficientStorage()) // status 507
                .andExpect(jsonPath("$.status", is("error")))
                .andExpect(jsonPath("$.errorCode", is("EVICTION_FAILED")))
                .andExpect(jsonPath("$.message", is("Cache capacity reached and eviction was unable to free memory.")));
    }

    @Test
    public void testGetHit() throws Exception {
        long expiryTime = System.currentTimeMillis() + 300_000L;
        CacheEntry entry = new CacheEntry("v1", System.currentTimeMillis(), expiryTime, System.currentTimeMillis(), 1);

        Mockito.when(cacheEngine.get("k1")).thenReturn(entry);

        mockMvc.perform(get("/api/v1/cache/k1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key", is("k1")))
                .andExpect(jsonPath("$.value", is("v1")))
                .andExpect(jsonPath("$.ttl_remaining").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    public void testGetMiss() throws Exception {
        Mockito.when(cacheEngine.get("k1")).thenReturn(null);

        mockMvc.perform(get("/api/v1/cache/k1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is("error")))
                .andExpect(jsonPath("$.errorCode", is("KEY_NOT_FOUND")))
                .andExpect(jsonPath("$.message", is("Requested key does not exist or has expired.")));
    }

    @Test
    public void testDeleteSuccess() throws Exception {
        CacheEntry entry = new CacheEntry("v1", System.currentTimeMillis());
        Mockito.when(cacheEngine.delete("k1")).thenReturn(entry);

        mockMvc.perform(delete("/api/v1/cache/k1"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    public void testDeleteMiss() throws Exception {
        Mockito.when(cacheEngine.delete("k1")).thenReturn(null);

        mockMvc.perform(delete("/api/v1/cache/k1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is("error")))
                .andExpect(jsonPath("$.errorCode", is("KEY_NOT_FOUND")))
                .andExpect(jsonPath("$.message", is("Cannot delete key: key does not exist.")));
    }

    @Test
    public void testExistsTrue() throws Exception {
        Mockito.when(cacheEngine.exists("k1")).thenReturn(true);

        mockMvc.perform(get("/api/v1/cache/k1/exists"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key", is("k1")))
                .andExpect(jsonPath("$.exists", is(true)));
    }

    @Test
    public void testExistsFalse() throws Exception {
        Mockito.when(cacheEngine.exists("k1")).thenReturn(false);

        mockMvc.perform(get("/api/v1/cache/k1/exists"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key", is("k1")))
                .andExpect(jsonPath("$.exists", is(false)));
    }

    @Test
    public void testExpireSuccess() throws Exception {
        CacheEntry entry = new CacheEntry("v1", System.currentTimeMillis());
        Mockito.when(cacheEngine.get("k1")).thenReturn(entry);

        CacheDtos.ExpireRequest request = new CacheDtos.ExpireRequest(600L);

        mockMvc.perform(post("/api/v1/cache/k1/expire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key", is("k1")))
                .andExpect(jsonPath("$.ttl_updated", is(600)));
    }

    @Test
    public void testTtlSuccess() throws Exception {
        long expiryTime = System.currentTimeMillis() + 600_000L;
        CacheEntry entry = new CacheEntry("v1", System.currentTimeMillis(), expiryTime, System.currentTimeMillis(), 1);
        Mockito.when(cacheEngine.get("k1")).thenReturn(entry);

        mockMvc.perform(get("/api/v1/cache/k1/ttl"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key", is("k1")))
                .andExpect(jsonPath("$.ttl_remaining").value(org.hamcrest.Matchers.greaterThan(0)));
    }
}

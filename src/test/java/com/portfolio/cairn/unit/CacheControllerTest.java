package com.portfolio.cairn.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.cairn.exception.EvictionFailedException;
import com.portfolio.cairn.exception.InvalidTtlException;
import com.portfolio.cairn.exception.KeyNotFoundException;
import com.portfolio.cairn.sharding.NodeRouter;
import com.portfolio.cairn.web.CacheController;
import com.portfolio.cairn.web.CacheDtos;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private NodeRouter nodeRouter;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    public void testSetSuccess() throws Exception {
        CacheDtos.SetRequest request = new CacheDtos.SetRequest("k1", "v1", 300L);
        CacheDtos.SetResponse responseBody = new CacheDtos.SetResponse("k1", "cached", 300L);
        Mockito.when(nodeRouter.set(any())).thenAnswer(inv -> ResponseEntity.ok(responseBody));

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

        mockMvc.perform(post("/api/v1/cache")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is("error")))
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("ttl: TTL must be positive")))
                .andExpect(jsonPath("$.timestamp", notNullValue()));
    }

    @Test
    public void testSetEvictionFailed() throws Exception {
        CacheDtos.SetRequest request = new CacheDtos.SetRequest("k1", "v1", null);

        Mockito.when(nodeRouter.set(any())).thenThrow(new EvictionFailedException("Cache capacity reached and eviction was unable to free memory."));

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
        CacheDtos.GetResponse responseBody = new CacheDtos.GetResponse("k1", "v1", 299L);
        Mockito.when(nodeRouter.get("k1")).thenAnswer(inv -> ResponseEntity.ok(responseBody));

        mockMvc.perform(get("/api/v1/cache/k1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key", is("k1")))
                .andExpect(jsonPath("$.value", is("v1")))
                .andExpect(jsonPath("$.ttl_remaining", is(299)));
    }

    @Test
    public void testGetMiss() throws Exception {
        Mockito.when(nodeRouter.get("k1")).thenThrow(new KeyNotFoundException("Requested key does not exist or has expired."));

        mockMvc.perform(get("/api/v1/cache/k1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is("error")))
                .andExpect(jsonPath("$.errorCode", is("KEY_NOT_FOUND")))
                .andExpect(jsonPath("$.message", is("Requested key does not exist or has expired.")));
    }

    @Test
    public void testDeleteSuccess() throws Exception {
        Mockito.when(nodeRouter.delete("k1")).thenReturn(ResponseEntity.noContent().build());

        mockMvc.perform(delete("/api/v1/cache/k1"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    public void testDeleteMiss() throws Exception {
        Mockito.when(nodeRouter.delete("k1")).thenThrow(new KeyNotFoundException("Cannot delete key: key does not exist."));

        mockMvc.perform(delete("/api/v1/cache/k1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is("error")))
                .andExpect(jsonPath("$.errorCode", is("KEY_NOT_FOUND")))
                .andExpect(jsonPath("$.message", is("Cannot delete key: key does not exist.")));
    }

    @Test
    public void testExistsTrue() throws Exception {
        CacheDtos.ExistsResponse responseBody = new CacheDtos.ExistsResponse("k1", true);
        Mockito.when(nodeRouter.exists("k1")).thenAnswer(inv -> ResponseEntity.ok(responseBody));

        mockMvc.perform(get("/api/v1/cache/k1/exists"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key", is("k1")))
                .andExpect(jsonPath("$.exists", is(true)));
    }

    @Test
    public void testExistsFalse() throws Exception {
        CacheDtos.ExistsResponse responseBody = new CacheDtos.ExistsResponse("k1", false);
        Mockito.when(nodeRouter.exists("k1")).thenAnswer(inv -> ResponseEntity.ok(responseBody));

        mockMvc.perform(get("/api/v1/cache/k1/exists"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key", is("k1")))
                .andExpect(jsonPath("$.exists", is(false)));
    }

    @Test
    public void testExpireSuccess() throws Exception {
        CacheDtos.ExpireRequest request = new CacheDtos.ExpireRequest(600L);
        CacheDtos.ExpireResponse responseBody = new CacheDtos.ExpireResponse("k1", 600L);
        Mockito.when(nodeRouter.expire(eq("k1"), any())).thenAnswer(inv -> ResponseEntity.ok(responseBody));

        mockMvc.perform(post("/api/v1/cache/k1/expire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key", is("k1")))
                .andExpect(jsonPath("$.ttl_updated", is(600)));
    }

    @Test
    public void testTtlSuccess() throws Exception {
        CacheDtos.TtlResponse responseBody = new CacheDtos.TtlResponse("k1", 600L);
        Mockito.when(nodeRouter.ttl("k1")).thenAnswer(inv -> ResponseEntity.ok(responseBody));

        mockMvc.perform(get("/api/v1/cache/k1/ttl"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key", is("k1")))
                .andExpect(jsonPath("$.ttl_remaining", is(600)));
    }

    @Test
    public void testSetBlankKey() throws Exception {
        CacheDtos.SetRequest request = new CacheDtos.SetRequest("   ", "v1", 300L);

        mockMvc.perform(post("/api/v1/cache")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is("error")))
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("key: Key must not be blank")));
    }

    @Test
    public void testSetOversizedKey() throws Exception {
        String longKey = "A".repeat(251);
        CacheDtos.SetRequest request = new CacheDtos.SetRequest(longKey, "v1", 300L);

        mockMvc.perform(post("/api/v1/cache")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is("error")))
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("key: Key must not exceed 250 characters")));
    }

    @Test
    public void testSetOversizedValue() throws Exception {
        String longVal = "A".repeat(1048577);
        CacheDtos.SetRequest request = new CacheDtos.SetRequest("k1", longVal, 300L);

        mockMvc.perform(post("/api/v1/cache")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is("error")))
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("value: Value must not exceed 1MB")));
    }

    @Test
    public void testSetNullValue() throws Exception {
        CacheDtos.SetRequest request = new CacheDtos.SetRequest("k1", null, 300L);

        mockMvc.perform(post("/api/v1/cache")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is("error")))
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("value: Value must not be null")));
    }

    @Test
    public void testSetValidationInvalidTtl() throws Exception {
        CacheDtos.SetRequest request = new CacheDtos.SetRequest("k1", "v1", 0L);

        mockMvc.perform(post("/api/v1/cache")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is("error")))
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("ttl: TTL must be positive")));
    }

    @Test
    public void testExpireValidationInvalidTtl() throws Exception {
        CacheDtos.ExpireRequest request = new CacheDtos.ExpireRequest(0L);

        mockMvc.perform(post("/api/v1/cache/k1/expire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is("error")))
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("ttl: TTL must be positive")));
    }

    @Test
    public void testInvalidateValidationBlankPattern() throws Exception {
        CacheDtos.InvalidateRequest request = new CacheDtos.InvalidateRequest("");

        mockMvc.perform(post("/api/v1/cache/invalidate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is("error")))
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("pattern: Pattern must not be blank")));
    }
}

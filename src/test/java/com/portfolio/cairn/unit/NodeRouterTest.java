package com.portfolio.cairn.unit;

import com.portfolio.cairn.engine.CacheEngine;
import com.portfolio.cairn.engine.evict.LruEvictionPolicy;
import com.portfolio.cairn.sharding.ConsistentHashRing;
import com.portfolio.cairn.sharding.NodeConfig;
import com.portfolio.cairn.sharding.NodeRouter;
import com.portfolio.cairn.web.CacheDtos;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

public class NodeRouterTest {

    private CacheEngine localCache;
    private ConsistentHashRing ring;
    private NodeConfig nodeConfig;
    private NodeRouter router;

    private HttpServer serverB;
    private HttpServer serverC;

    private String addressB;
    private String addressC;

    @BeforeEach
    public void setUp() throws IOException {
        // Start two lightweight HttpServer instances to represent remote nodes Node-B and Node-C
        serverB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverC = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        addressB = "http://127.0.0.1:" + serverB.getAddress().getPort();
        addressC = "http://127.0.0.1:" + serverC.getAddress().getPort();

        // Stub remote endpoints
        setUpHttpServer(serverB, "Node-B-Value");
        setUpHttpServer(serverC, "Node-C-Value");

        serverB.start();
        serverC.start();

        // Setup local cache
        localCache = new CacheEngine(new LruEvictionPolicy(), 1000);

        // Setup ConsistentHashRing
        ring = new ConsistentHashRing();
        ring.addNode("Node-A", 10);
        ring.addNode("Node-B", 10);
        ring.addNode("Node-C", 10);

        // Setup NodeConfig stub
        nodeConfig = new NodeConfig() {
            @Override
            public String getLocalNodeId() {
                return "Node-A";
            }

            @Override
            public List<ClusterNode> getNodes() {
                List<ClusterNode> list = new ArrayList<>();
                ClusterNode nA = new ClusterNode();
                nA.setNodeId("Node-A");
                nA.setAddress("http://127.0.0.1:8081");
                list.add(nA);

                ClusterNode nB = new ClusterNode();
                nB.setNodeId("Node-B");
                nB.setAddress(addressB);
                list.add(nB);

                ClusterNode nC = new ClusterNode();
                nC.setNodeId("Node-C");
                nC.setAddress(addressC);
                list.add(nC);
                return list;
            }
        };

        // Create router with WebClient builder
        router = new NodeRouter(ring, localCache, nodeConfig, WebClient.builder());
    }

    @AfterEach
    public void tearDown() {
        if (serverB != null) serverB.stop(0);
        if (serverC != null) serverC.stop(0);
    }

    private void setUpHttpServer(HttpServer server, String mockResponseValue) {
        // Mock get handler
        server.createContext("/api/v1/cache/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String key = path.substring(path.lastIndexOf('/') + 1);
            byte[] response;
            int status = 200;

            if (exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                if (path.endsWith("/exists")) {
                    response = "{\"key\":\"test\",\"exists\":true}".getBytes(StandardCharsets.UTF_8);
                } else if (path.endsWith("/ttl")) {
                    response = "{\"key\":\"test\",\"ttl_remaining\":100}".getBytes(StandardCharsets.UTF_8);
                } else {
                    response = String.format("{\"key\":\"%s\",\"value\":\"%s\",\"ttl_remaining\":100}", key, mockResponseValue)
                            .getBytes(StandardCharsets.UTF_8);
                }
            } else if (exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                if (path.endsWith("/expire")) {
                    response = "{\"key\":\"test\",\"ttl_updated\":120}".getBytes(StandardCharsets.UTF_8);
                } else {
                    response = "{\"key\":\"test\",\"status\":\"success\"}".getBytes(StandardCharsets.UTF_8);
                }
            } else if (exchange.getRequestMethod().equalsIgnoreCase("DELETE")) {
                status = 204;
                response = new byte[0];
            } else {
                status = 405;
                response = "Method Not Allowed".getBytes(StandardCharsets.UTF_8);
            }

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            if (response.length > 0) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response);
                }
            }
            exchange.close();
        });

        // Mock post (set) handler
        server.createContext("/api/v1/cache", exchange -> {
            byte[] response = "{\"key\":\"test\",\"status\":\"success\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
            exchange.close();
        });
    }

    @Test
    public void testLocalRouting() {
        // Find a key that hashes to Node-A (local)
        String localKey = null;
        for (int i = 0; i < 1000; i++) {
            String key = "key-local-" + i;
            if ("Node-A".equals(ring.getNode(key))) {
                localKey = key;
                break;
            }
        }
        assertThat(localKey).isNotNull();

        // Put in local cache directly to simulate pre-existing value
        localCache.set(localKey, "local-value");

        // When: requesting via router
        ResponseEntity<?> response = router.get(localKey);

        // Then: it should return the local value and be a CacheDtos.GetResponse
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        CacheDtos.GetResponse body = (CacheDtos.GetResponse) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.key()).isEqualTo(localKey);
        assertThat(body.value()).isEqualTo("local-value");
    }

    @Test
    public void testRemoteRouting() {
        // Find a key that hashes to Node-B (remote)
        String keyNodeB = null;
        for (int i = 0; i < 1000; i++) {
            String key = "key-node-b-" + i;
            if ("Node-B".equals(ring.getNode(key))) {
                keyNodeB = key;
                break;
            }
        }
        assertThat(keyNodeB).isNotNull();

        // When: requesting via router
        ResponseEntity<?> response = router.get(keyNodeB);

        // Then: it should proxy to Node-B and return the response from HttpServer B
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        // The response body was deserialized from remote string body
        String body = (String) response.getBody();
        assertThat(body).contains("Node-B-Value");
    }

    @Test
    public void testConcurrentProxiedRequests() throws InterruptedException {
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < 100; j++) {
                        String key = "concurrent-key-" + index + "-" + j;
                        router.set(new CacheDtos.SetRequest(key, "val-" + j, null));
                        ResponseEntity<?> response = router.get(key);
                        if (response.getStatusCode().is2xxSuccessful()) {
                            successCount.incrementAndGet();
                        } else {
                            errorCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(errorCount.get()).isEqualTo(0);
        assertThat(successCount.get()).isEqualTo(threadCount * 100);
    }
}

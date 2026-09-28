// 文件说明：测试 UpstreamModelDiscoveryServiceTest 的预期行为与异常场景，防止相关功能回归。
package org.unreal.modelrouter.config.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.config.dto.ModelDiscoveryRequest;
import org.unreal.modelrouter.config.dto.ModelDiscoveryResult;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UpstreamModelDiscoveryServiceTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> responseBody = new AtomicReference<>();
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> requestHeader = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", this::handleModelsRequest);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void discoversModelsFromCompletionUrlAndForwardsHeaders() {
        responseBody.set("""
                {"object":"list","data":[
                  {"id":"model-b","object":"model","owned_by":"vendor-b"},
                  {"id":"model-a","object":"model","owned_by":"vendor-a"},
                  {"id":"MODEL-A","object":"model","owned_by":"duplicate"}
                ]}
                """);

        ModelDiscoveryResult result = discover(ModelDiscoveryRequest.builder()
                .baseUrl(baseUrl + "/v1/chat/completions")
                .headers(Map.of("X-Discovery-Test", "header-ok"))
                .build());

        assertEquals("/v1/models", requestPath.get());
        assertEquals("header-ok", requestHeader.get());
        assertEquals(baseUrl + "/v1/models", result.getModelsUrl());
        assertEquals(2, result.getCount());
        assertEquals("model-a", result.getModels().get(0).getId());
        assertEquals("vendor-a", result.getModels().get(0).getOwnedBy());
        assertEquals("model-b", result.getModels().get(1).getId());
    }

    @Test
    void supportsModelsArrayAndNameField() {
        responseBody.set("""
                {"models":[{"name":"zeta"},{"model":"alpha"},"beta"]}
                """);

        ModelDiscoveryResult result = discover(ModelDiscoveryRequest.builder()
                .baseUrl(baseUrl)
                .build());

        assertEquals(3, result.getCount());
        assertEquals("alpha", result.getModels().get(0).getId());
        assertEquals("beta", result.getModels().get(1).getId());
        assertEquals("zeta", result.getModels().get(2).getId());
    }

    @Test
    void rejectsResponseWithoutModels() {
        responseBody.set("{\"data\":[]}");

        UpstreamModelDiscoveryException error = assertThrows(
                UpstreamModelDiscoveryException.class,
                () -> discover(ModelDiscoveryRequest.builder().baseUrl(baseUrl).build()));

        assertEquals("上游响应中没有找到模型列表，请确认接口返回 data 或 models 数组", error.getMessage());
    }

    @Test
    void rejectsNonHttpBaseUrl() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new UpstreamModelDiscoveryService().discover(ModelDiscoveryRequest.builder()
                        .baseUrl("file:///tmp/models.json")
                        .build()));

        assertEquals("基础 URL 必须是有效的 HTTP 或 HTTPS 地址", error.getMessage());
    }

    @Test
    void mapsUpstreamHttpErrorToDiscoveryException() {
        responseStatus.set(401);
        responseBody.set("{\"error\":\"unauthorized\"}");

        RuntimeException error = assertThrows(
                RuntimeException.class,
                () -> discover(ModelDiscoveryRequest.builder().baseUrl(baseUrl).build()));

        UpstreamModelDiscoveryException discoveryError = assertInstanceOf(
                UpstreamModelDiscoveryException.class, error);
        assertEquals("上游模型接口返回 HTTP 401: {\"error\":\"unauthorized\"}", discoveryError.getMessage());
    }

    private ModelDiscoveryResult discover(final ModelDiscoveryRequest request) {
        return new UpstreamModelDiscoveryService().discover(request).block(Duration.ofSeconds(5));
    }

    private void handleModelsRequest(final HttpExchange exchange) throws IOException {
        requestPath.set(exchange.getRequestURI().getPath());
        requestHeader.set(exchange.getRequestHeaders().getFirst("X-Discovery-Test"));
        byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus.get(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}

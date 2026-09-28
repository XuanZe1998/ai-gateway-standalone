// 文件说明：测试 TeacherEndpointPathFilterTest 的预期行为与异常场景，防止相关功能回归。
package org.unreal.modelrouter.auth.teacher;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.unreal.modelrouter.auth.teacher.config.TeacherAccessProperties;
import org.unreal.modelrouter.auth.teacher.security.TeacherEndpointPathFilter;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeacherEndpointPathFilterTest {

    @Test
    void rewritesTeacherUrlAndPreservesEndpointIdentity() {
        TeacherAccessProperties properties = new TeacherAccessProperties();
        properties.setEnabled(true);
        TeacherEndpointPathFilter filter = new TeacherEndpointPathFilter(properties);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post(
                        "/u/AbCdEfGhIjKlMnOpQrStUvWx/v1/chat/completions?stream=true").build());
        AtomicReference<String> observedPath = new AtomicReference<>();

        filter.filter(exchange, filteredExchange -> {
            observedPath.set(filteredExchange.getRequest().getURI().getRawPath());
            return reactor.core.publisher.Mono.empty();
        }).block();

        assertEquals("/api/v1/chat/completions", observedPath.get());
        assertEquals("AbCdEfGhIjKlMnOpQrStUvWx",
                exchange.getAttribute(TeacherEndpointPathFilter.ENDPOINT_ID_ATTRIBUTE));
        assertTrue(filter.getOrder() < -100,
                "教师专属路径必须在 Spring Security 之前重写");
    }
}

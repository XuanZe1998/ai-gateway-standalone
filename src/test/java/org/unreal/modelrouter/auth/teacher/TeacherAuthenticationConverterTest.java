// 文件说明：测试 TeacherAuthenticationConverterTest 的预期行为与异常场景，防止相关功能回归。
package org.unreal.modelrouter.auth.teacher;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.Authentication;
import org.unreal.modelrouter.auth.filter.DefaultAuthenticationConverter;
import org.unreal.modelrouter.auth.security.config.properties.SecurityProperties;
import org.unreal.modelrouter.auth.teacher.config.TeacherAccessProperties;
import org.unreal.modelrouter.auth.teacher.model.TeacherAccessAuthentication;
import org.unreal.modelrouter.auth.teacher.security.ClientCertificateFingerprintExtractor;
import org.unreal.modelrouter.auth.teacher.security.TeacherEndpointPathFilter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class TeacherAuthenticationConverterTest {

    @Test
    void recognizesTeacherTokenBeforeLegacyApiKeyFallback() {
        TeacherAccessProperties teacherProperties = new TeacherAccessProperties();
        teacherProperties.setEnabled(true);
        DefaultAuthenticationConverter converter = new DefaultAuthenticationConverter(
                new SecurityProperties(), teacherProperties,
                new ClientCertificateFingerprintExtractor());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/chat/completions")
                        .header("Authorization", "Bearer ntit_at_signed-token")
                        .build());
        exchange.getAttributes().put(
                TeacherEndpointPathFilter.ENDPOINT_ID_ATTRIBUTE, "teacher-endpoint");

        Authentication authentication = converter.convert(exchange).block();

        TeacherAccessAuthentication teacherAuthentication = assertInstanceOf(
                TeacherAccessAuthentication.class, authentication);
        assertEquals("teacher-endpoint", teacherAuthentication.getRequestProof().endpointId());
        assertNull(teacherAuthentication.getRequestProof().certificateFingerprint());
    }
}

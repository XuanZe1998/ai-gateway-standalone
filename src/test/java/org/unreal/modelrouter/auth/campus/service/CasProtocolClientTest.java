// 文件说明：测试 CasProtocolClientTest 的预期行为与异常场景，防止相关功能回归。
package org.unreal.modelrouter.auth.campus.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.auth.campus.config.CampusAuthProperties;
import org.unreal.modelrouter.auth.campus.model.CasIdentity;
import org.unreal.modelrouter.common.exception.AuthenticationException;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CasProtocolClientTest {
    @Test
    void sendsTheExactServiceUrlWithoutEncodingItTwice() throws Exception {
        var received = new java.util.concurrent.atomic.AtomicReference<java.util.Map<String, String>>();
        var server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cas/serviceValidate", exchange -> {
            var query = new java.util.HashMap<String, String>();
            for (String pair : exchange.getRequestURI().getRawQuery().split("&")) {
                String[] parts = pair.split("=", 2);
                query.put(parts[0], java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
            received.set(query);
            byte[] response = xml("""
                    <cas:serviceResponse xmlns:cas="http://www.yale.edu/tp/cas">
                      <cas:authenticationSuccess><cas:user>teacher01</cas:user></cas:authenticationSuccess>
                    </cas:serviceResponse>
                    """);
            exchange.getResponseHeaders().set("Content-Type", "application/xml");
            exchange.sendResponseHeaders(200, response.length);
            try (var body = exchange.getResponseBody()) { body.write(response); }
        });
        server.start();
        try {
            properties.setCasBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/cas");
            String service = "https://localhost:39444/api/auth/cas/callback?state=sample_state-123";
            new CasProtocolClient(properties).validate("ST-test", service).block(java.time.Duration.ofSeconds(5));
            assertEquals(service, received.get().get("service"));
            assertEquals("ST-test", received.get().get("ticket"));
        } finally { server.stop(0); }
    }

    private CampusAuthProperties properties;
    private CasProtocolClient client;

    @BeforeEach
    void setUp() {
        properties = new CampusAuthProperties();
        properties.setMaxResponseBytes(4096);
        client = new CasProtocolClient(properties);
    }

    @Test
    void parsesSuccessfulCas20Response() {
        CasIdentity identity = client.parse(xml("""
                <cas:serviceResponse xmlns:cas="http://www.yale.edu/tp/cas">
                  <cas:authenticationSuccess>
                    <cas:user>cas-subject</cas:user>
                    <cas:attributes>
                      <cas:account>teacher01</cas:account>
                      <cas:localAccount>T1001</cas:localAccount>
                      <cas:typeCode>teacher-code</cas:typeCode>
                    </cas:attributes>
                  </cas:authenticationSuccess>
                </cas:serviceResponse>
                """));

        assertEquals("cas-subject", identity.subject());
        assertEquals("teacher01", identity.attribute("account"));
        assertEquals("T1001", identity.attribute("localAccount"));
    }

    @Test
    void rejectsAuthenticationFailure() {
        AuthenticationException error = assertThrows(AuthenticationException.class, () -> client.parse(xml("""
                <cas:serviceResponse xmlns:cas="http://www.yale.edu/tp/cas">
                  <cas:authenticationFailure code="INVALID_TICKET">invalid</cas:authenticationFailure>
                </cas:serviceResponse>
                """)));

        assertEquals("CAS_TICKET_INVALID", error.getErrorCode());
    }

    @Test
    void rejectsDoctypeAndExternalEntity() {
        AuthenticationException error = assertThrows(AuthenticationException.class, () -> client.parse(xml("""
                <?xml version="1.0"?>
                <!DOCTYPE foo [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
                <cas:serviceResponse xmlns:cas="http://www.yale.edu/tp/cas">
                  <cas:authenticationSuccess><cas:user>&xxe;</cas:user></cas:authenticationSuccess>
                </cas:serviceResponse>
                """)));

        assertEquals("CAS_XML_INVALID", error.getErrorCode());
    }

    @Test
    void rejectsOversizedResponseBeforeParsing() {
        properties.setMaxResponseBytes(32);
        AuthenticationException error = assertThrows(AuthenticationException.class,
                () -> client.parse(new byte[33]));

        assertEquals("CAS_RESPONSE_INVALID", error.getErrorCode());
    }

    private byte[] xml(final String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}

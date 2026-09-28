// 文件说明：测试 SpaCsrfTokenRequestHandlerTest 的预期行为与异常场景，防止相关功能回归。
package org.unreal.modelrouter.auth.security.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.security.web.server.csrf.CsrfWebFilter;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class SpaCsrfTokenRequestHandlerTest {
    private WebTestClient client() {
        var filter = new CsrfWebFilter();
        filter.setCsrfTokenRepository(CookieServerCsrfTokenRepository.withHttpOnlyFalse());
        filter.setRequestHandler(new SpaCsrfTokenRequestHandler());
        return WebTestClient.bindToWebHandler(exchange -> {
            Mono<CsrfToken> token = exchange.getAttribute(CsrfToken.class.getName());
            return token.doOnNext(value -> exchange.getResponse().getHeaders()
                    .set("X-Masked-Token", value.getToken())).then(exchange.getResponse().setComplete());
        }).webFilter(filter).build();
    }

    @Test
    void acceptsRawCookieHeaderUsedByAxios() {
        var client = client();
        var response = client.get().uri("/").exchange().expectStatus().isOk().returnResult(String.class);
        String cookie = response.getResponseCookies().getFirst("XSRF-TOKEN").getValue();
        client.post().uri("/").cookie("XSRF-TOKEN", cookie).header("X-XSRF-TOKEN", cookie)
                .exchange().expectStatus().isOk();
    }

    @Test
    void stillAcceptsMaskedTokenExposedInSessionResponse() {
        var client = client();
        var response = client.get().uri("/").exchange().expectStatus().isOk().returnResult(String.class);
        String cookie = response.getResponseCookies().getFirst("XSRF-TOKEN").getValue();
        String masked = response.getResponseHeaders().getFirst("X-Masked-Token");
        client.post().uri("/").cookie("XSRF-TOKEN", cookie).header("X-XSRF-TOKEN", masked)
                .exchange().expectStatus().isOk();
    }

    @Test
    void rejectsMissingAndIncorrectHeader() {
        var client = client();
        var response = client.get().uri("/").exchange().expectStatus().isOk().returnResult(String.class);
        String cookie = response.getResponseCookies().getFirst("XSRF-TOKEN").getValue();
        client.post().uri("/").cookie("XSRF-TOKEN", cookie).exchange().expectStatus().isForbidden();
        client.post().uri("/").cookie("XSRF-TOKEN", cookie).header("X-XSRF-TOKEN", "wrong")
                .exchange().expectStatus().isForbidden();
    }
}

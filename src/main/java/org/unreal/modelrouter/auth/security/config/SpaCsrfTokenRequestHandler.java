package org.unreal.modelrouter.auth.security.config;

import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestHandler;
import org.springframework.security.web.server.csrf.XorServerCsrfTokenRequestAttributeHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Accept Axios's cookie/header token while retaining masked tokens in responses/forms. */
final class SpaCsrfTokenRequestHandler implements ServerCsrfTokenRequestHandler {
    private final XorServerCsrfTokenRequestAttributeHandler masked =
            new XorServerCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(final ServerWebExchange exchange, final Mono<CsrfToken> csrfToken) {
        masked.handle(exchange, csrfToken);
    }

    @Override
    public Mono<String> resolveCsrfTokenValue(final ServerWebExchange exchange, final CsrfToken csrfToken) {
        String header = exchange.getRequest().getHeaders().getFirst(csrfToken.getHeaderName());
        if (header != null && MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8),
                csrfToken.getToken().getBytes(StandardCharsets.UTF_8))) {
            return Mono.just(header);
        }
        return masked.resolveCsrfTokenValue(exchange, csrfToken);
    }
}

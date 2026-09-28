package org.unreal.modelrouter.auth.teacher.security;

import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.unreal.modelrouter.auth.teacher.config.TeacherAccessProperties;
import reactor.core.publisher.Mono;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将教师专属 URL 重写到现有 OpenAI 兼容接口。 */
@Component
@RequiredArgsConstructor
public class TeacherEndpointPathFilter implements WebFilter, Ordered {

    public static final String ENDPOINT_ID_ATTRIBUTE = "teacher.endpointId";
    /** Spring Boot's security WebFilter is normally ordered at -100. */
    public static final int FILTER_ORDER = -101;
    private static final Pattern ENDPOINT_PATTERN = Pattern.compile(
            "^/u/([A-Za-z0-9_-]{20,64})/(v1(?:/.*)?)$");

    private final TeacherAccessProperties properties;

    @Override
    public int getOrder() {
        return FILTER_ORDER;
    }

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
        if (!properties.isEnabled()) {
            return chain.filter(exchange);
        }

        Matcher matcher = ENDPOINT_PATTERN.matcher(exchange.getRequest().getPath().value());
        if (!matcher.matches()) {
            return chain.filter(exchange);
        }

        exchange.getAttributes().put(ENDPOINT_ID_ATTRIBUTE, matcher.group(1));
        ServerHttpRequest request = exchange.getRequest().mutate()
                .path("/api/" + matcher.group(2))
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }
}

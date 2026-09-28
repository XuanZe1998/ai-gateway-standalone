package org.unreal.modelrouter.config.core;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * 全局 CORS 配置
 *
 * <p>在 Spring WebFlux 中，控制器级别的 @CrossOrigin 对 OPTIONS 预检请求基本无效
 *（因为预检是 OPTIONS 方法，而接口都是 @PostMapping，请求匹配不到 handler）。
 * 因此必须在过滤器层面统一处理 CORS，且要在 Spring Security 过滤器之前生效。</p>
 */
@Configuration
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class GlobalCorsConfiguration {

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();
        // 允许任意 Origin（生产环境建议收紧为具体域名）
        config.addAllowedOriginPattern("*");
        // 允许所有 HTTP 方法
        config.addAllowedMethod("*");
        // 允许所有请求头（包含 Authorization、X-API-Key、Content-Type 等）
        config.addAllowedHeader("*");
        // 允许暴露的响应头（SSE 流式场景需要）
        config.addExposedHeader("*");
        // 预检结果缓存 1 小时，减少重复 OPTIONS 请求
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return new CorsWebFilter(source);
    }
}

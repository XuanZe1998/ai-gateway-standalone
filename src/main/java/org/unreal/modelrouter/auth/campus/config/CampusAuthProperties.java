package org.unreal.modelrouter.auth.campus.config;



import jakarta.annotation.PostConstruct;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

import org.springframework.stereotype.Component;



import java.net.URI;

import java.time.Duration;

import java.util.ArrayList;


import java.util.List;




/** 学校 CAS 统一身份认证配置。 */

@Data

@Component

@ConfigurationProperties(prefix = "jairouter.campus-auth")

public class CampusAuthProperties {

    private boolean enabled;

    private String casBaseUrl = "https://cas.ntit.edu.cn/cas/";

    private String publicBaseUrl;

    private String callbackPath = "/api/auth/cas/callback";

    private List<String> adminAccounts = new ArrayList<>();

    /** Temporarily grant ADMIN to every successfully authenticated CAS user. */
    private boolean defaultAdmin;

    /** Temporary CAS-only access without real-name/balance checks or balance deduction. */
    private boolean unrestrictedAccess;

    private List<String> allowedTargetPrefixes = List.of("/admin");

    private List<String> modelPermissions = List.of(

            "READ", "WRITE", "CHAT", "EMBEDDING", "RERANK",

            "TTS", "STT", "IMGGEN", "IMGEDIT", "VIDGEN");

    private Duration sessionTimeout = Duration.ofMinutes(30);

    private Duration stateTtl = Duration.ofMinutes(5);

    private Duration connectTimeout = Duration.ofSeconds(5);

    private Duration responseTimeout = Duration.ofSeconds(8);

    private int maxResponseBytes = 262144;
    private boolean cookieSecure = true;

    private EmergencyLogin emergencyLogin = new EmergencyLogin();



    @PostConstruct

    public void validate() {

        if (!enabled) {

            return;

        }

        URI casUri = requireHttpUrl(casBaseUrl, "cas-base-url", false);

        URI publicUri = requireHttpUrl(publicBaseUrl, "public-base-url", true);

        if (!"https".equalsIgnoreCase(casUri.getScheme())) {

            throw new IllegalStateException("校园 CAS cas-base-url 必须使用 HTTPS");

        }

        if (!"https".equalsIgnoreCase(publicUri.getScheme())

                && !isLoopbackHost(publicUri.getHost())) {

            throw new IllegalStateException("校园 CAS public-base-url 必须使用 HTTPS（本机开发环境除外）");

        }

        if (isBlank(callbackPath) || !callbackPath.startsWith("/") || callbackPath.startsWith("//")) {

            throw new IllegalStateException("校园 CAS 回调路径配置无效");

        }

        if (allowedTargetPrefixes == null || allowedTargetPrefixes.isEmpty()

                || allowedTargetPrefixes.stream().anyMatch(value -> value == null

                || !value.startsWith("/") || value.startsWith("//"))) {

            throw new IllegalStateException("校园 CAS 登录后目标路径白名单配置无效");

        }

        if (maxResponseBytes < 1024 || stateTtl.isNegative() || stateTtl.isZero()

                || sessionTimeout.isNegative() || sessionTimeout.isZero()) {

            throw new IllegalStateException("校园 CAS 响应大小或会话时长配置无效");

        }

    }



    public String callbackServiceUrl() {

        return publicBaseUrl.replaceAll("/+$", "") + callbackPath;

    }



    public String identityProviderUrl() {

        return casBaseUrl.replaceAll("/+$", "");

    }



    private URI requireHttpUrl(final String value, final String property, final boolean localhostAllowed) {

        if (isBlank(value)) {

            throw new IllegalStateException("启用校园 CAS 时必须配置 jairouter.campus-auth." + property);

        }

        try {

            URI uri = URI.create(value);

            boolean http = "https".equalsIgnoreCase(uri.getScheme())

                    || (localhostAllowed && "http".equalsIgnoreCase(uri.getScheme())

                    && isLoopbackHost(uri.getHost()));

            if (!http || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {

                throw new IllegalArgumentException();

            }

            return uri;

        } catch (IllegalArgumentException exception) {

            throw new IllegalStateException("校园 CAS " + property + " 配置无效", exception);

        }

    }



    private boolean isLoopbackHost(final String host) {

        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);

    }



    private static boolean isBlank(final String value) {

        return value == null || value.isBlank();

    }



    @Data

    public static class EmergencyLogin {

        private boolean enabled;

        private List<String> allowedCidrs = List.of("127.0.0.1/32", "::1/128");
        private List<String> trustedProxyCidrs = new ArrayList<>();

    }

}



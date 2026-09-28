package org.unreal.modelrouter.config.core;

import com.fasterxml.jackson.databind.JsonNode;
import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.core.codec.DecodingException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.unreal.modelrouter.config.dto.DiscoveredModelDTO;
import org.unreal.modelrouter.config.dto.ModelDiscoveryRequest;
import org.unreal.modelrouter.config.dto.ModelDiscoveryResult;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * 从 OpenAI 兼容上游的模型列表接口发现模型。
 */
@Slf4j
@Service
public class UpstreamModelDiscoveryService {

    private static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_MODELS = 2000;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Set<String> BLOCKED_HEADERS = Set.of(
            HttpHeaders.HOST.toLowerCase(Locale.ROOT),
            HttpHeaders.CONTENT_LENGTH.toLowerCase(Locale.ROOT),
            HttpHeaders.CONNECTION.toLowerCase(Locale.ROOT),
            HttpHeaders.TRANSFER_ENCODING.toLowerCase(Locale.ROOT)
    );

    private final WebClient webClient;

    public UpstreamModelDiscoveryService() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(REQUEST_TIMEOUT);
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
                .build();
    }

    /**
     * 请求上游模型列表并转换为统一格式。
     *
     * @param request 发现请求
     * @return 发现结果
     */
    public Mono<ModelDiscoveryResult> discover(final ModelDiscoveryRequest request) {
        URI modelsUri = resolveModelsUri(request);
        log.info("开始发现上游模型: {}", modelsUri);

        WebClient.RequestHeadersSpec<?> requestSpec = webClient.get()
                .uri(modelsUri)
                .accept(MediaType.APPLICATION_JSON);
        requestSpec.headers(headers -> copyHeaders(request.getHeaders(), headers));

        return requestSpec.exchangeToMono(response -> handleResponse(response.statusCode(), response))
                .timeout(REQUEST_TIMEOUT)
                .map(body -> toResult(modelsUri, body))
                .onErrorMap(TimeoutException.class, error -> new UpstreamModelDiscoveryException(
                        "请求上游模型接口超时（20 秒）", error))
                .onErrorMap(WebClientRequestException.class, error -> new UpstreamModelDiscoveryException(
                        "无法连接上游模型接口: " + safeMessage(error), error))
                .onErrorMap(DecodingException.class, error -> new UpstreamModelDiscoveryException(
                        "上游模型接口未返回有效的 JSON", error))
                .onErrorMap(DataBufferLimitException.class, error -> new UpstreamModelDiscoveryException(
                        "上游模型接口响应超过 10 MB 限制", error));
    }

    private Mono<JsonNode> handleResponse(
            final HttpStatusCode statusCode,
            final org.springframework.web.reactive.function.client.ClientResponse response) {
        if (statusCode.is2xxSuccessful()) {
            return response.bodyToMono(JsonNode.class)
                    .switchIfEmpty(Mono.error(new UpstreamModelDiscoveryException("上游模型接口返回空响应")));
        }
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMap(body -> Mono.error(new UpstreamModelDiscoveryException(
                        "上游模型接口返回 HTTP " + statusCode.value() + formatErrorBody(body))));
    }

    private String safeMessage(final Throwable error) {
        return StringUtils.hasText(error.getMessage()) ? error.getMessage() : error.getClass().getSimpleName();
    }

    private String formatErrorBody(final String body) {
        if (!StringUtils.hasText(body)) {
            return "";
        }
        String normalized = body.replaceAll("\\s+", " ").trim();
        int maxLength = 300;
        return ": " + (normalized.length() > maxLength
                ? normalized.substring(0, maxLength) + "..."
                : normalized);
    }

    private void copyHeaders(final Map<String, String> source, final HttpHeaders target) {
        if (source == null) {
            return;
        }
        source.forEach((name, value) -> {
            if (!StringUtils.hasText(name) || value == null) {
                return;
            }
            String normalizedName = name.trim();
            if (!BLOCKED_HEADERS.contains(normalizedName.toLowerCase(Locale.ROOT))) {
                target.set(normalizedName, value);
            }
        });
    }

    private ModelDiscoveryResult toResult(final URI modelsUri, final JsonNode body) {
        List<DiscoveredModelDTO> models = extractModels(body);
        if (models.isEmpty()) {
            throw new UpstreamModelDiscoveryException("上游响应中没有找到模型列表，请确认接口返回 data 或 models 数组");
        }
        return ModelDiscoveryResult.builder()
                .modelsUrl(modelsUri.toString())
                .count(models.size())
                .models(models)
                .build();
    }

    private List<DiscoveredModelDTO> extractModels(final JsonNode body) {
        JsonNode modelArray = findModelArray(body);
        if (modelArray == null || !modelArray.isArray()) {
            return List.of();
        }

        Map<String, DiscoveredModelDTO> uniqueModels = new LinkedHashMap<>();
        for (JsonNode item : modelArray) {
            String id = extractModelId(item);
            if (!StringUtils.hasText(id)) {
                continue;
            }
            String normalizedId = id.trim();
            uniqueModels.putIfAbsent(normalizedId.toLowerCase(Locale.ROOT), DiscoveredModelDTO.builder()
                    .id(normalizedId)
                    .object(textValue(item, "object"))
                    .ownedBy(firstTextValue(item, "owned_by", "ownedBy", "provider"))
                    .build());
            if (uniqueModels.size() >= MAX_MODELS) {
                break;
            }
        }

        List<DiscoveredModelDTO> result = new ArrayList<>(uniqueModels.values());
        result.sort(Comparator.comparing(DiscoveredModelDTO::getId, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private JsonNode findModelArray(final JsonNode body) {
        if (body == null) {
            return null;
        }
        if (body.isArray()) {
            return body;
        }
        if (body.path("data").isArray()) {
            return body.path("data");
        }
        if (body.path("models").isArray()) {
            return body.path("models");
        }
        return null;
    }

    private String extractModelId(final JsonNode item) {
        if (item == null) {
            return null;
        }
        if (item.isTextual()) {
            return item.asText();
        }
        return firstTextValue(item, "id", "name", "model");
    }

    private String firstTextValue(final JsonNode node, final String... fieldNames) {
        for (String fieldName : fieldNames) {
            String value = textValue(node, fieldName);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String textValue(final JsonNode node, final String fieldName) {
        if (node != null && node.path(fieldName).isValueNode()) {
            return node.path(fieldName).asText(null);
        }
        return null;
    }

    private URI resolveModelsUri(final ModelDiscoveryRequest request) {
        if (request == null || !StringUtils.hasText(request.getBaseUrl())) {
            throw new IllegalArgumentException("基础 URL 不能为空");
        }

        URI baseUri = parseHttpUri(request.getBaseUrl().trim(), "基础 URL");
        if (StringUtils.hasText(request.getModelsUrl())) {
            String configuredModelsUrl = request.getModelsUrl().trim();
            URI modelsUri = configuredModelsUrl.startsWith("/")
                    ? resolveAbsolutePath(baseUri, configuredModelsUrl)
                    : baseUri.resolve(configuredModelsUrl);
            validateHttpUri(modelsUri, "模型列表 URL");
            return modelsUri;
        }

        String path = baseUri.getPath() == null ? "" : baseUri.getPath();
        String normalizedPath = path.replaceAll("/+$", "");
        String modelsPath;
        int v1Index = normalizedPath.lastIndexOf("/v1");
        if (v1Index >= 0 && isPathBoundary(normalizedPath, v1Index + 3)) {
            modelsPath = normalizedPath.substring(0, v1Index) + "/v1/models";
        } else if (normalizedPath.endsWith("/models")) {
            modelsPath = normalizedPath;
        } else {
            modelsPath = (normalizedPath.isEmpty() ? "" : normalizedPath) + "/v1/models";
        }

        try {
            return new URI(baseUri.getScheme(), baseUri.getUserInfo(), baseUri.getHost(), baseUri.getPort(),
                    modelsPath, null, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("无法生成模型列表 URL", e);
        }
    }

    private URI resolveAbsolutePath(final URI baseUri, final String path) {
        try {
            return new URI(baseUri.getScheme(), baseUri.getUserInfo(), baseUri.getHost(), baseUri.getPort(),
                    path, null, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("模型列表 URL 格式不正确", e);
        }
    }

    private boolean isPathBoundary(final String path, final int index) {
        return index == path.length() || path.charAt(index) == '/';
    }

    private URI parseHttpUri(final String value, final String fieldName) {
        try {
            URI uri = new URI(value);
            validateHttpUri(uri, fieldName);
            return uri;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(fieldName + " 格式不正确", e);
        }
    }

    private void validateHttpUri(final URI uri, final String fieldName) {
        String scheme = uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || uri.getHost() == null) {
            throw new IllegalArgumentException(fieldName + " 必须是有效的 HTTP 或 HTTPS 地址");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException(fieldName + " 不允许在 URL 中包含用户名或密码");
        }
    }
}

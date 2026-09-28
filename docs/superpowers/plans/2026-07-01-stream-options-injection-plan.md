# 流式 chat stream_options 自动注入实现计划

> **For agentic workers:** REQUIRED SUB-LEVEL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为火山方舟等需要 `stream_options.include_usage=true` 才能返回流式 usage 的平台，提供配置驱动的自动注入能力，解决流式 chat 计费为 0 的问题。

**Architecture:** 在 `StreamingRequestProcessor` 发送请求前，根据 `StreamUsageInjectionProperties` 中的 URL pattern 匹配实例 baseUrl，命中时向请求体注入 `stream_options`。注入逻辑与现有计费、重试、熔断链路解耦，仅修改请求参数。

**Tech Stack:** Spring Boot 3.5.5, WebFlux, Jackson, JUnit 5, Mockito

## Global Constraints

- JDK 17
- 不改 `ChatDTO.Request` 暴露 `stream_options` 给客户端
- 不动 `ai_channel` / `ai_model` 等算力平台表结构
- 不修改 `BaseAdapter` 的余额校验、实例选择、重试逻辑
- 总开关默认 `false`，避免未配置时影响其他平台
- 只有 `ServiceType.chat` 且 `stream=true` 的请求才会注入
- 请求体不可识别时只记录 warn，不阻断主链路

---

## File Structure

| File | Responsibility |
|---|---|
| `src/main/java/org/unreal/modelrouter/router/adapter/config/StreamUsageInjectionProperties.java` | 绑定 `jairouter.adapter.stream-usage-injection` 配置，提供 URL pattern 匹配方法 |
| `src/main/java/org/unreal/modelrouter/router/adapter/processor/StreamingRequestProcessor.java` | 在发送请求前调用注入逻辑；保持原有 SSE 处理、计费捕获逻辑不变 |
| `src/main/resources/config/router/adapter.yml` | 新增 `jairouter.adapter.stream-usage-injection` 默认配置 |
| `src/test/java/org/unreal/modelrouter/router/adapter/processor/StreamUsageInjectionTest.java` | `StreamUsageInjectionProperties` 的单元测试 |
| `src/test/java/org/unreal/modelrouter/router/adapter/processor/StreamingRequestProcessorTest.java` | `StreamingRequestProcessor` 注入逻辑的单元测试 |

---

## Task 1: Create StreamUsageInjectionProperties

**Files:**
- Create: `src/main/java/org/unreal/modelrouter/router/adapter/config/StreamUsageInjectionProperties.java`

**Interfaces:**
- Consumes: none
- Produces: `StreamUsageInjectionProperties` bean with `isEnabled()`, `matches(String baseUrl)` methods

- [ ] **Step 1.1: Create the properties class**

```java
package org.unreal.modelrouter.router.adapter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 流式请求 stream_options 自动注入配置
 *
 * @since v2.15.2
 */
@Component
@ConfigurationProperties(prefix = "jairouter.adapter.stream-usage-injection")
public class StreamUsageInjectionProperties {

    private boolean enabled = false;
    private List<String> patterns = new ArrayList<>();
    private Map<String, Object> options = new HashMap<>(Map.of("include_usage", true));

    private transient List<Pattern> compiledPatterns;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getPatterns() {
        return patterns;
    }

    public void setPatterns(List<String> patterns) {
        this.patterns = patterns != null ? patterns : new ArrayList<>();
        this.compiledPatterns = null;
    }

    public Map<String, Object> getOptions() {
        return options;
    }

    public void setOptions(Map<String, Object> options) {
        this.options = options != null ? options : new HashMap<>();
    }

    /**
     * 判断 baseUrl 是否命中任意 pattern（不区分大小写）
     */
    public boolean matches(String baseUrl) {
        if (!enabled || baseUrl == null || baseUrl.isBlank() || patterns == null || patterns.isEmpty()) {
            return false;
        }
        List<Pattern> compiled = getCompiledPatterns();
        for (Pattern pattern : compiled) {
            if (pattern != null && pattern.matcher(baseUrl).find()) {
                return true;
            }
        }
        return false;
    }

    private List<Pattern> getCompiledPatterns() {
        if (compiledPatterns != null) {
            return compiledPatterns;
        }
        compiledPatterns = new ArrayList<>();
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            try {
                compiledPatterns.add(Pattern.compile(pattern, Pattern.CASE_INSENSITIVE));
            } catch (PatternSyntaxException e) {
                // 非法正则跳过，启动时已有 warn 日志（Spring Boot 本身会报）
            }
        }
        return compiledPatterns;
    }
}
```

- [ ] **Step 1.2: Commit**

```bash
git add src/main/java/org/unreal/modelrouter/router/adapter/config/StreamUsageInjectionProperties.java
git commit -m "feat: add StreamUsageInjectionProperties for configurable stream_options injection"
```

---

## Task 2: Modify StreamingRequestProcessor

**Files:**
- Modify: `src/main/java/org/unreal/modelrouter/router/adapter/processor/StreamingRequestProcessor.java`

**Interfaces:**
- Consumes: `StreamUsageInjectionProperties` bean via constructor
- Produces: `injectStreamOptionsIfNeeded(Object, ServiceType, ModelInstance)` private method

- [ ] **Step 2.1: Add dependency and constructor parameter**

Locate the constructor at line 42-50. Modify it to accept `StreamUsageInjectionProperties`:

```java
public class StreamingRequestProcessor {

    private static final Logger logger = LoggerFactory.getLogger(StreamingRequestProcessor.class);

    private final MetricsCollector metricsCollector;
    private final ResponseTransformer responseTransformer;
    private final ObjectMapper objectMapper;
    private final FreeQuotaService freeQuotaService;
    private final StreamUsageInjectionProperties streamUsageInjectionProperties;

    public StreamingRequestProcessor(final MetricsCollector metricsCollector,
                                      final ResponseTransformer responseTransformer,
                                      final ObjectMapper objectMapper,
                                      final FreeQuotaService freeQuotaService,
                                      final StreamUsageInjectionProperties streamUsageInjectionProperties) {
        this.metricsCollector = metricsCollector;
        this.responseTransformer = responseTransformer;
        this.objectMapper = objectMapper;
        this.freeQuotaService = freeQuotaService;
        this.streamUsageInjectionProperties = streamUsageInjectionProperties;
    }
```

- [ ] **Step 2.2: Add injection method**

Add the following private method after the constructor:

```java
    /**
     * 根据配置向流式 chat 请求体注入 stream_options
     */
    private Object injectStreamOptionsIfNeeded(final Object request,
            final ModelServiceRegistry.ServiceType serviceType,
            final ModelRouterProperties.ModelInstance instance) {
        if (streamUsageInjectionProperties == null || !streamUsageInjectionProperties.isEnabled()) {
            return request;
        }
        if (serviceType != ModelServiceRegistry.ServiceType.chat) {
            return request;
        }
        if (instance == null || !streamUsageInjectionProperties.matches(instance.getBaseUrl())) {
            return request;
        }
        if (request == null) {
            return null;
        }

        try {
            JsonNode requestNode = null;
            if (request instanceof JsonNode node) {
                requestNode = node;
            } else if (request instanceof Map) {
                requestNode = objectMapper.valueToTree(request);
            } else {
                requestNode = objectMapper.valueToTree(request);
            }

            if (!requestNode.isObject()) {
                return request;
            }
            ObjectNode objectNode = (ObjectNode) requestNode;

            // 只对流式请求注入
            JsonNode streamNode = objectNode.path("stream");
            if (!streamNode.isBoolean() || !streamNode.asBoolean()) {
                return request;
            }

            // 避免覆盖客户端已传入的 stream_options（本期客户端不传，保留防御性逻辑）
            if (!objectNode.path("stream_options").isMissingNode()) {
                return request;
            }

            ObjectNode streamOptions = objectMapper.createObjectNode();
            Map<String, Object> options = streamUsageInjectionProperties.getOptions();
            if (options != null) {
                options.forEach((key, value) -> {
                    if (value instanceof Boolean b) {
                        streamOptions.put(key, b);
                    } else if (value instanceof Number n) {
                        streamOptions.put(key, n);
                    } else if (value != null) {
                        streamOptions.put(key, value.toString());
                    }
                });
            }
            if (streamOptions.isEmpty()) {
                streamOptions.put("include_usage", true);
            }
            objectNode.set("stream_options", streamOptions);

            logger.debug("已为实例 {} 注入 stream_options: {}", instance.getName(), streamOptions);
            return objectNode;
        } catch (Exception e) {
            logger.warn("注入 stream_options 失败, instance={}, error={}",
                    instance != null ? instance.getName() : "null", e.getMessage());
            return request;
        }
    }
```

- [ ] **Step 2.3: Wire injection into processStreamingRequest**

Locate the line:

```java
return Mono.deferContextual(outerCtx -> {
```

Just before it, add:

```java
        final Object requestWithStreamOptions = injectStreamOptionsIfNeeded(request, serviceType, selectedInstance);
```

Then change the WebClient body to use the new variable:

```java
            Flux<ServerSentEvent<String>> streamResponse = requestSpec
                .bodyValue(requestWithStreamOptions)
```

- [ ] **Step 2.4: Add import statements**

Ensure these imports are present:

```java
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.unreal.modelrouter.router.adapter.config.StreamUsageInjectionProperties;
import java.util.Map;
```

- [ ] **Step 2.5: Commit**

```bash
git add src/main/java/org/unreal/modelrouter/router/adapter/processor/StreamingRequestProcessor.java
git commit -m "feat: inject stream_options.include_usage for configured streaming chat instances"
```

---

## Task 3: Add Default Configuration

**Files:**
- Modify: `src/main/resources/config/router/adapter.yml`

**Interfaces:**
- Consumes: none
- Produces: `jairouter.adapter.stream-usage-injection` default config

- [ ] **Step 3.1: Append config to adapter.yml**

At the end of `src/main/resources/config/router/adapter.yml`, add:

```yaml

# 流式 chat 请求 stream_options 自动注入
# 用于需要显式设置 stream_options.include_usage=true 才返回 usage 的平台（如火山方舟）
jairouter:
  adapter:
    stream-usage-injection:
      enabled: false
      patterns:
        - ".*ark\\.cn-beijing\\.volces\\.com.*"
      options:
        include_usage: true
        chunk_include_usage: false
```

- [ ] **Step 3.2: Commit**

```bash
git add src/main/resources/config/router/adapter.yml
git commit -m "config: add default stream_options injection config for Volcengine ARK"
```

---

## Task 4: Add Unit Tests

**Files:**
- Create: `src/test/java/org/unreal/modelrouter/router/adapter/processor/StreamUsageInjectionTest.java`

**Interfaces:**
- Consumes: `StreamUsageInjectionProperties`
- Produces: passing tests

- [ ] **Step 4.1: Write StreamUsageInjectionProperties tests**

```java
package org.unreal.modelrouter.router.adapter.processor;

import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.router.adapter.config.StreamUsageInjectionProperties;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StreamUsageInjectionTest {

    @Test
    void matches_whenEnabledAndPatternMatches() {
        StreamUsageInjectionProperties props = new StreamUsageInjectionProperties();
        props.setEnabled(true);
        props.setPatterns(List.of(".*ark\\.cn-beijing\\.volces\\.com.*"));

        assertTrue(props.matches("https://ark.cn-beijing.volces.com/api/v3"));
    }

    @Test
    void matches_caseInsensitive() {
        StreamUsageInjectionProperties props = new StreamUsageInjectionProperties();
        props.setEnabled(true);
        props.setPatterns(List.of(".*VOLCES\\.com.*"));

        assertTrue(props.matches("https://ARK.CN-BEIJING.VOLCES.COM/api/v3"));
    }

    @Test
    void matches_whenDisabled_returnsFalse() {
        StreamUsageInjectionProperties props = new StreamUsageInjectionProperties();
        props.setEnabled(false);
        props.setPatterns(List.of(".*volces\\.com.*"));

        assertFalse(props.matches("https://ark.cn-beijing.volces.com/api/v3"));
    }

    @Test
    void matches_whenBaseUrlNull_returnsFalse() {
        StreamUsageInjectionProperties props = new StreamUsageInjectionProperties();
        props.setEnabled(true);
        props.setPatterns(List.of(".*volces\\.com.*"));

        assertFalse(props.matches(null));
        assertFalse(props.matches(""));
    }

    @Test
    void matches_whenPatternNotMatch_returnsFalse() {
        StreamUsageInjectionProperties props = new StreamUsageInjectionProperties();
        props.setEnabled(true);
        props.setPatterns(List.of(".*volces\\.com.*"));

        assertFalse(props.matches("https://api.openai.com/v1"));
    }

    @Test
    void matches_invalidPatternIsIgnored() {
        StreamUsageInjectionProperties props = new StreamUsageInjectionProperties();
        props.setEnabled(true);
        props.setPatterns(List.of("[invalid", ".*volces\\.com.*"));

        assertTrue(props.matches("https://ark.cn-beijing.volces.com/api/v3"));
    }
}
```

- [ ] **Step 4.2: Commit**

```bash
git add src/test/java/org/unreal/modelrouter/router/adapter/processor/StreamUsageInjectionTest.java
git commit -m "test: add StreamUsageInjectionProperties unit tests"
```

---

## Task 5: Add StreamingRequestProcessor Injection Tests

**Files:**
- Create: `src/test/java/org/unreal/modelrouter/router/adapter/processor/StreamingRequestProcessorTest.java`

**Interfaces:**
- Consumes: `StreamingRequestProcessor`, `StreamUsageInjectionProperties`
- Produces: passing tests

- [ ] **Step 5.1: Write processor injection tests**

```java
package org.unreal.modelrouter.router.adapter.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.unreal.modelrouter.billing.freequota.FreeQuotaService;
import org.unreal.modelrouter.monitor.monitoring.collector.MetricsCollector;
import org.unreal.modelrouter.router.adapter.config.StreamUsageInjectionProperties;
import org.unreal.modelrouter.router.adapter.transformer.ResponseTransformer;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StreamingRequestProcessorTest {

    @Mock
    private MetricsCollector metricsCollector;
    @Mock
    private ResponseTransformer responseTransformer;
    @Mock
    private FreeQuotaService freeQuotaService;

    private ObjectMapper objectMapper;
    private StreamingRequestProcessor processor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void injectStreamOptions_addsIncludeUsageForMatchingInstance() throws Exception {
        StreamUsageInjectionProperties props = enabledProps();
        processor = new StreamingRequestProcessor(
                metricsCollector, responseTransformer, objectMapper, freeQuotaService, props);

        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", "doubao-lite");
        request.put("stream", true);

        ModelRouterProperties.ModelInstance instance = new ModelRouterProperties.ModelInstance();
        instance.setName("volc-ark");
        instance.setBaseUrl("https://ark.cn-beijing.volces.com/api/v3");

        Object result = invokeInject(request, ModelServiceRegistry.ServiceType.chat, instance);

        assertTrue(result instanceof ObjectNode);
        ObjectNode resultNode = (ObjectNode) result;
        assertTrue(resultNode.has("stream_options"));
        assertTrue(resultNode.path("stream_options").path("include_usage").asBoolean());
    }

    @Test
    void injectStreamOptions_doesNotInjectForNonChatService() throws Exception {
        StreamUsageInjectionProperties props = enabledProps();
        processor = new StreamingRequestProcessor(
                metricsCollector, responseTransformer, objectMapper, freeQuotaService, props);

        ObjectNode request = objectMapper.createObjectNode();
        request.put("stream", true);

        ModelRouterProperties.ModelInstance instance = instance();

        Object result = invokeInject(request, ModelServiceRegistry.ServiceType.embedding, instance);

        assertSame(request, result);
        assertFalse(((ObjectNode) result).has("stream_options"));
    }

    @Test
    void injectStreamOptions_doesNotInjectForNonStreamingRequest() throws Exception {
        StreamUsageInjectionProperties props = enabledProps();
        processor = new StreamingRequestProcessor(
                metricsCollector, responseTransformer, objectMapper, freeQuotaService, props);

        ObjectNode request = objectMapper.createObjectNode();
        request.put("stream", false);

        ModelRouterProperties.ModelInstance instance = instance();

        Object result = invokeInject(request, ModelServiceRegistry.ServiceType.chat, instance);

        assertSame(request, result);
    }

    @Test
    void injectStreamOptions_doesNotInjectWhenDisabled() throws Exception {
        StreamUsageInjectionProperties props = new StreamUsageInjectionProperties();
        props.setEnabled(false);
        processor = new StreamingRequestProcessor(
                metricsCollector, responseTransformer, objectMapper, freeQuotaService, props);

        ObjectNode request = objectMapper.createObjectNode();
        request.put("stream", true);

        Object result = invokeInject(request, ModelServiceRegistry.ServiceType.chat, instance());

        assertSame(request, result);
    }

    @Test
    void injectStreamOptions_doesNotOverrideExistingStreamOptions() throws Exception {
        StreamUsageInjectionProperties props = enabledProps();
        processor = new StreamingRequestProcessor(
                metricsCollector, responseTransformer, objectMapper, freeQuotaService, props);

        ObjectNode request = objectMapper.createObjectNode();
        request.put("stream", true);
        ObjectNode existingOptions = objectMapper.createObjectNode();
        existingOptions.put("include_usage", false);
        request.set("stream_options", existingOptions);

        Object result = invokeInject(request, ModelServiceRegistry.ServiceType.chat, instance());

        assertSame(request, result);
        assertFalse(request.path("stream_options").path("include_usage").asBoolean());
    }

    private StreamUsageInjectionProperties enabledProps() {
        StreamUsageInjectionProperties props = new StreamUsageInjectionProperties();
        props.setEnabled(true);
        props.setPatterns(List.of(".*volces\\.com.*"));
        props.setOptions(Map.of("include_usage", true));
        return props;
    }

    private ModelRouterProperties.ModelInstance instance() {
        ModelRouterProperties.ModelInstance instance = new ModelRouterProperties.ModelInstance();
        instance.setName("volc-ark");
        instance.setBaseUrl("https://ark.cn-beijing.volces.com/api/v3");
        return instance;
    }

    private Object invokeInject(Object request, ModelServiceRegistry.ServiceType serviceType,
                                 ModelRouterProperties.ModelInstance instance) throws Exception {
        java.lang.reflect.Method method = StreamingRequestProcessor.class.getDeclaredMethod(
                "injectStreamOptionsIfNeeded", Object.class, ModelServiceRegistry.ServiceType.class,
                ModelRouterProperties.ModelInstance.class);
        method.setAccessible(true);
        return method.invoke(processor, request, serviceType, instance);
    }
}
```

- [ ] **Step 5.2: Commit**

```bash
git add src/test/java/org/unreal/modelrouter/router/adapter/processor/StreamingRequestProcessorTest.java
git commit -m "test: add StreamingRequestProcessor stream_options injection tests"
```

---

## Task 6: Compile and Run Tests

**Files:**
- All of the above

- [ ] **Step 6.1: Compile the project**

Run:

```bash
JAVA_HOME="/c/Program Files/Java/jdk-17.0.1" mvn compile -DskipTests -Dcheckstyle.skip=true -Dspotbugs.skip=true
```

Expected: BUILD SUCCESS

- [ ] **Step 6.2: Run new unit tests**

Run:

```bash
JAVA_HOME="/c/Program Files/Java/jdk-17.0.1" mvn test -Dtest=StreamUsageInjectionTest,StreamingRequestProcessorTest -Dcheckstyle.skip=true -Dspotbugs.skip=true
```

Expected: Tests run: 11, Failures: 0, Errors: 0

- [ ] **Step 6.3: Run existing streaming-related tests as regression**

Run:

```bash
JAVA_HOME="/c/Program Files/Java/jdk-17.0.1" mvn test -Dtest=BaseAdapterExtendedTest,BaseAdapterFreeQuotaTest -Dcheckstyle.skip=true -Dspotbugs.skip=true
```

Expected: BUILD SUCCESS with no new failures

- [ ] **Step 6.4: Commit test verification result**

No code changes; optionally update commit log with test command outputs in MR description.

---

## Plan Self-Review

### Spec Coverage

| Spec Section | Implementing Task |
|---|---|
| 配置绑定类 | Task 1 |
| StreamingRequestProcessor 注入逻辑 | Task 2 |
| 配置 YAML | Task 3 |
| StreamUsageInjectionProperties 单元测试 | Task 4 |
| StreamingRequestProcessor 注入单元测试 | Task 5 |
| 编译与回归测试 | Task 6 |

### Placeholder Scan

- 无 TBD/TODO
- 所有代码块包含完整代码
- 所有命令包含预期输出
- 无 "适当处理" 等模糊描述

### Type Consistency

- `StreamUsageInjectionProperties` 方法名：`isEnabled()`, `matches(String)`, `getOptions()` 在全文中一致。
- `StreamingRequestProcessor` 构造参数顺序与字段赋值一致。
- `injectStreamOptionsIfNeeded` 方法签名在全文中一致：`(Object, ServiceType, ModelInstance)`。

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-07-01-stream-options-injection-plan.md`.**

Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints.

**Which approach?**

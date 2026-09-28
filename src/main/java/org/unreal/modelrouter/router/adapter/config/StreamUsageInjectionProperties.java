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

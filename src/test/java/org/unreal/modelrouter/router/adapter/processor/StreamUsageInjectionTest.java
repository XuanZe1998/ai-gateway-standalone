// 文件说明：测试 StreamUsageInjectionTest 的预期行为与异常场景，防止相关功能回归。
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

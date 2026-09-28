// 文件说明：测试 CampusAuthPropertiesTest 的预期行为与异常场景，防止相关功能回归。
package org.unreal.modelrouter.auth.campus.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class CampusAuthPropertiesTest {

    @Test
    void casCanBeEnabledWithoutTypeCodeMappings() {
        CampusAuthProperties properties = new CampusAuthProperties();
        properties.setEnabled(true);
        properties.setPublicBaseUrl("https://gordanshop.com");

        assertDoesNotThrow(properties::validate);
    }
}

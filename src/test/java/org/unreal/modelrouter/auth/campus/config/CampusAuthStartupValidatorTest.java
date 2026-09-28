// 文件说明：测试 CampusAuthStartupValidatorTest 的预期行为与异常场景，防止相关功能回归。
package org.unreal.modelrouter.auth.campus.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CampusAuthStartupValidatorTest {

    @Test
    void requiresRedisForClusterProfile() {
        CampusAuthProperties properties = new CampusAuthProperties();
        properties.setEnabled(true);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.session.store-type", "none");
        environment.setActiveProfiles("cluster", "cas");

        CampusAuthStartupValidator validator = new CampusAuthStartupValidator(properties, environment);

        assertThrows(IllegalStateException.class, validator::validateProductionSessionStore);
    }

    @Test
    void acceptsRedisForClusterProfile() {
        CampusAuthProperties properties = new CampusAuthProperties();
        properties.setEnabled(true);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.session.store-type", "redis");
        environment.setActiveProfiles("cluster", "cas");

        CampusAuthStartupValidator validator = new CampusAuthStartupValidator(properties, environment);

        assertDoesNotThrow(validator::validateProductionSessionStore);
    }

    @Test
    void permitsNonRedisForLocalDisabledCas() {
        CampusAuthProperties properties = new CampusAuthProperties();
        properties.setEnabled(false);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.session.store-type", "none");
        environment.setActiveProfiles("standalone");

        CampusAuthStartupValidator validator = new CampusAuthStartupValidator(properties, environment);

        assertDoesNotThrow(validator::validateProductionSessionStore);
    }
}

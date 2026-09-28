// 文件说明：测试 TeacherAccessTokenServiceConfigurationTest 的预期行为与异常场景，防止相关功能回归。
package org.unreal.modelrouter.auth.teacher;

import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.auth.teacher.config.TeacherAccessProperties;
import org.unreal.modelrouter.auth.teacher.service.TeacherAccessTokenService;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TeacherAccessTokenServiceConfigurationTest {

    @Test
    void acceptsCompleteEnabledConfiguration() {
        TeacherAccessProperties properties = validProperties();

        assertDoesNotThrow(() -> new TeacherAccessTokenService(properties).validateConfiguration());
    }

    @Test
    void rejectsShortIndependentSigningSecret() {
        TeacherAccessProperties properties = validProperties();
        properties.setTokenSecret("too-short");

        assertThrows(IllegalStateException.class,
                () -> new TeacherAccessTokenService(properties).validateConfiguration());
    }

    @Test
    void rejectsInvalidDeviceLimit() {
        TeacherAccessProperties properties = validProperties();
        properties.setMaxDevicesPerTeacher(0);

        assertThrows(IllegalStateException.class,
                () -> new TeacherAccessTokenService(properties).validateConfiguration());
    }

    private TeacherAccessProperties validProperties() {
        TeacherAccessProperties properties = new TeacherAccessProperties();
        properties.setEnabled(true);
        properties.setTokenSecret("teacher-access-test-secret-32-bytes-minimum");
        properties.setAccessTokenTtl(Duration.ofMinutes(5));
        properties.setMaxDevicesPerTeacher(2);
        return properties;
    }
}

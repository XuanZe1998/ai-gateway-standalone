// 文件说明：测试 FreeQuotaPropertiesTest 的预期行为与异常场景，防止相关功能回归。
package org.unreal.modelrouter.billing.freequota;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FreeQuotaPropertiesTest {

    @Test
    void selectsHighestQuotaAcrossCampusRoles() {
        FreeQuotaProperties properties = new FreeQuotaProperties();

        FreeQuotaProperties.QuotaPolicy policy = properties.resolvePolicy(
                List.of("ROLE_STUDENT", "teacher"));

        assertThat(policy.tier()).isEqualTo("TEACHER");
        assertThat(policy.total()).isEqualTo(5_000_000L);
    }

    @Test
    void defaultsUnknownRolesToStudentTier() {
        FreeQuotaProperties properties = new FreeQuotaProperties();

        FreeQuotaProperties.QuotaPolicy policy = properties.resolvePolicy(List.of("ALUMNI"));

        assertThat(policy.tier()).isEqualTo("STUDENT");
        assertThat(policy.total()).isEqualTo(1_000_000L);
    }
}

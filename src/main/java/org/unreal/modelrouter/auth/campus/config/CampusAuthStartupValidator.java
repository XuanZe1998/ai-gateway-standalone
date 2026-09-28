package org.unreal.modelrouter.auth.campus.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/** Enforces deployment invariants that cannot be checked by property binding alone. */
@Component
@RequiredArgsConstructor
@Slf4j
public class CampusAuthStartupValidator {
    private final CampusAuthProperties properties;
    private final Environment environment;

    @PostConstruct
    public void validateProductionSessionStore() {
        if (!properties.isEnabled()) {
            return;
        }
        if (properties.isDefaultAdmin()) {
            log.warn("CAS 默认管理员权限已启用：所有通过校园统一认证的用户均可访问后台管理。"
                    + "请及时关闭 CAMPUS_DEFAULT_ADMIN 并改用 CAMPUS_ADMIN_ACCOUNTS 白名单。");
        }
        if (properties.isUnrestrictedAccess()) {
            log.warn("CAS 临时无限制访问已启用：未实名/无余额用户可调用付费模型；"
                    + "本地不扣余额，但上游供应商可能产生实际费用。请监控用量并及时关闭 CAMPUS_UNRESTRICTED_ACCESS。");
        }
        boolean production = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> "prod".equalsIgnoreCase(profile)
                        || "production".equalsIgnoreCase(profile)
                        || "cluster".equalsIgnoreCase(profile));
        String storeType = environment.getProperty("spring.session.store-type", "");
        if (production && !"redis".equalsIgnoreCase(storeType)) {
            throw new IllegalStateException(
                    "生产或集群环境启用校园 CAS 时必须配置 spring.session.store-type=redis");
        }
    }
}

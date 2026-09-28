package org.unreal.modelrouter.persistence.jpa;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;
import org.unreal.modelrouter.auth.security.service.ApiKeyService;

/**
 * JPA 数据库初始化器
 * v1.5.x: 纯 JPA 方式初始化数据库，加载 YAML 配置到数据库
 */
@Slf4j
@Component
@RequiredArgsConstructor
@DependsOn({"apiKeyService"})
public class JpaDatabaseInitializer {

    private final ApiKeyService apiKeyService;

    @PostConstruct
    public void initialize() {
        log.info("Initializing JPA database...");

        try {
            // 初始化 API Keys（使用 StoreManager 存储，不依赖数据库表）
            initializeApiKeys();
            log.info("JPA database initialization completed");
        } catch (Exception e) {
            log.warn("JPA database initialization skipped: {}", e.getMessage());
        }
    }

    /**
     * 初始化 API Keys
     */
    private void initializeApiKeys() {
        try {
            log.info("Initializing API Keys...");
            if (apiKeyService.hasPersistedAccountConfig()) {
                log.info("Loading persisted API Keys configuration...");
                apiKeyService.loadLatestApiKeyConfig();
            } else {
                log.info("No persisted API Keys found, initializing from YAML...");
                apiKeyService.initializeApiKeyFromYaml();
            }
            log.info("API Keys initialization completed");
        } catch (Exception e) {
            log.error("Failed to initialize API Keys: {}", e.getMessage(), e);
        }
    }
}
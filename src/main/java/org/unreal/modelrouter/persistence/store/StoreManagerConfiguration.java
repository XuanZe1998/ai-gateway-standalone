package org.unreal.modelrouter.persistence.store;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * StoreManager 配置类。
 *
 * <p>文件存储只适用于单节点。集群部署必须设置 {@code store.type=jpa}，
 * 此时由 {@code JpaStoreManager} 提供共享配置存储。</p>
 */
@Slf4j
@Configuration
@ConfigurationProperties(prefix = "store")
public class StoreManagerConfiguration {

    private String type = "file";
    private String path = "./config";

    /**
     * 创建文件 StoreManager。仅在显式选择 file 或没有设置类型时启用。
     * @return StoreManager实例
     */
    @Bean("fileStoreManager")
    @ConditionalOnProperty(name = "store.type", havingValue = "file", matchIfMissing = true)
    public StoreManager fileStoreManager() {
        log.info("Initializing StoreManager with FileStorage (path: {})", path);
        return new FileStoreManager(path);
    }

    /**
     * 创建内存 StoreManager，供无持久化的测试或临时环境使用。
     * @return StoreManager实例
     */
    @Bean("memoryStoreManager")
    @ConditionalOnProperty(name = "store.type", havingValue = "memory")
    public StoreManager memoryStoreManager() {
        log.warn("Initializing non-persistent in-memory StoreManager");
        return new MemoryStoreManager();
    }

    // Getters and Setters
    public String getType() {
        return type;
    }

    public void setType(final String type) {
        this.type = type;
    }

    public String getPath() {
        return path;
    }

    public void setPath(final String path) {
        this.path = path;
    }
}

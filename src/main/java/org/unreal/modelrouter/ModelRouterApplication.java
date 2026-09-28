// 文件说明：Spring Boot 网关启动入口，启用异步任务并扫描配置属性。
package org.unreal.modelrouter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@ConfigurationPropertiesScan("org.unreal.modelrouter.config")
public class ModelRouterApplication {
    /** Private constructor to prevent instantiation. */
    private ModelRouterApplication() {}

    public static void main(final String[] args) {
        SpringApplication.run(ModelRouterApplication.class, args);
    }

}

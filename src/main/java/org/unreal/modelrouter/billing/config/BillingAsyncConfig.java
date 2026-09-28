package org.unreal.modelrouter.billing.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 计费异步线程池配置。
 * 默认 Spring SimpleAsyncTaskExecutor 不限制线程数，高并发时可能 OOM。
 * 改为有界 ThreadPoolTaskExecutor，队列满时由调用线程执行（CallerRunsPolicy），
 * 既不丢弃计费记录也不创建无限线程。
 */
@Configuration
@EnableAsync
public class BillingAsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(BillingAsyncConfig.class);

    @Override
    public Executor getAsyncExecutor() {
        return billingTaskExecutor();
    }

    @Bean(name = "billingTaskExecutor")
    public Executor billingTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("billing-");
        executor.setRejectedExecutionHandler(callerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("计费线程池初始化完成: corePoolSize=2, maxPoolSize=8, queueCapacity=500");
        return executor;
    }

    private RejectedExecutionHandler callerRunsPolicy() {
        return (Runnable r, ThreadPoolExecutor e) -> {
            log.warn("计费线程池队列已满，由调用线程执行（背压），activeCount={}, queueSize={}",
                    e.getActiveCount(), e.getQueue().size());
            if (!e.isShutdown()) {
                r.run();
            }
        };
    }
}

package com.example.autransactional.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Kira entrega cada webhook UNA sola vez, sin reintentos, y aborta a los 30 segundos.
 * Por eso el ingress responde 2xx de inmediato y el procesamiento ocurre en este pool.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    public static final String WEBHOOK_EXECUTOR = "webhookExecutor";

    @Bean(name = WEBHOOK_EXECUTOR)
    public ThreadPoolTaskExecutor webhookExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("kira-webhook-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }
}

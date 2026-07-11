package com.mall.seckill.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class SeckillOrderOutboxTaskConfiguration {

    @Bean("seckillOrderOutboxExecutor")
    public ThreadPoolTaskExecutor seckillOrderOutboxExecutor(SeckillProperties properties) {
        SeckillProperties.OrderOutbox outbox = properties.getOrderOutbox();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, outbox.getWorkerCorePoolSize()));
        executor.setMaxPoolSize(Math.max(executor.getCorePoolSize(), outbox.getWorkerMaxPoolSize()));
        executor.setQueueCapacity(Math.max(1, outbox.getWorkerQueueCapacity()));
        executor.setThreadNamePrefix("seckill-outbox-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    @Bean("seckillOutboxRecoveryScheduler")
    public ThreadPoolTaskScheduler seckillOutboxRecoveryScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("seckill-outbox-recovery-");
        scheduler.initialize();
        return scheduler;
    }
}

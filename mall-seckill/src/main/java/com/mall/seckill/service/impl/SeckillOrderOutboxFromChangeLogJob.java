package com.mall.seckill.service.impl;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "mall.seckill.order-outbox", name = "enabled", havingValue = "true")
public class SeckillOrderOutboxFromChangeLogJob {

    private final SeckillOrderOutboxFromChangeLogService service;

    public SeckillOrderOutboxFromChangeLogJob(SeckillOrderOutboxFromChangeLogService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${mall.seckill.order-outbox.fixed-delay:1000}")
    public void drain() {
        service.drainOnce();
    }
}

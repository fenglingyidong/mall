package com.mall.seckill.service.impl;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "mall.seckill.order-outbox", name = "enabled", havingValue = "true")
public class SeckillOrderOutboxFromChangeLogJob {

    private final SeckillOrderOutboxCoordinator coordinator;

    public SeckillOrderOutboxFromChangeLogJob(SeckillOrderOutboxCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Scheduled(
            fixedDelayString = "${mall.seckill.order-outbox.recovery-fixed-delay:1000}",
            scheduler = "seckillOutboxRecoveryScheduler")
    public void drain() {
        coordinator.recoverConfiguredShards();
    }
}

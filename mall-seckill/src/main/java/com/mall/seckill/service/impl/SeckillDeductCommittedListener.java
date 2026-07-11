package com.mall.seckill.service.impl;

import com.mall.seckill.service.event.SeckillDeductCommittedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class SeckillDeductCommittedListener {

    private final SeckillOrderOutboxCoordinator coordinator;

    public SeckillDeductCommittedListener(SeckillOrderOutboxCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeductCommitted(SeckillDeductCommittedEvent event) {
        if (event == null) {
            return;
        }
        coordinator.signal(event.bucketShardKey());
    }
}

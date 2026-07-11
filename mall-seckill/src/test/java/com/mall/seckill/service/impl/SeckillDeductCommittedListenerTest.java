package com.mall.seckill.service.impl;

import com.mall.seckill.service.event.SeckillDeductCommittedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SeckillDeductCommittedListenerTest {

    @Mock
    private SeckillOrderOutboxCoordinator coordinator;

    @Test
    void onDeductCommittedShouldSignalCoordinatorByShard() {
        SeckillDeductCommittedListener listener = new SeckillDeductCommittedListener(coordinator);

        listener.onDeductCommitted(new SeckillDeductCommittedEvent("r1", 3L));

        verify(coordinator).signal(3L);
    }
}

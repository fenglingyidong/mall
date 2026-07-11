package com.mall.seckill.service.impl;

import com.mall.message.MessageNames;
import com.mall.message.ReliableMessage;
import com.mall.message.ReliableMessagePublisher;
import com.mall.message.ReliableMessageRepository;
import com.mall.seckill.config.SeckillProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillOrderCreateMessageCompensationJobTest {

    @Mock
    private ReliableMessageRepository repository;

    @Mock
    private ReliableMessagePublisher publisher;

    @Test
    void compensateShouldFilterByShardAndRoutingKey() {
        SeckillProperties properties = new SeckillProperties();
        properties.getBucket().getRouting().setBucketShardKeys(List.of(1L, 3L));
        properties.getOrderOutbox().setBatchSize(40);
        properties.getOrderOutbox().setMessageDispatchTimeoutSeconds(5);
        ReliableMessage message = ReliableMessage.of(
                "mall.exchange",
                MessageNames.SECKILL_ORDER_CREATE_ROUTING_KEY,
                "r1",
                "{}",
                3L,
                null);
        SeckillOrderCreateMessageCompensationJob job =
                new SeckillOrderCreateMessageCompensationJob(repository, publisher, properties);
        when(repository.findNeedCompensation(1L, MessageNames.SECKILL_ORDER_CREATE_ROUTING_KEY, 40)).thenReturn(List.of());
        when(repository.findNeedCompensation(3L, MessageNames.SECKILL_ORDER_CREATE_ROUTING_KEY, 40)).thenReturn(List.of(message));

        job.compensate();

        verify(repository).markDispatchingTimedOut(any(Instant.class), eq(1L), eq(MessageNames.SECKILL_ORDER_CREATE_ROUTING_KEY), eq(40));
        verify(repository).markDispatchingTimedOut(any(Instant.class), eq(3L), eq(MessageNames.SECKILL_ORDER_CREATE_ROUTING_KEY), eq(40));
        verify(repository).findNeedCompensation(1L, MessageNames.SECKILL_ORDER_CREATE_ROUTING_KEY, 40);
        verify(repository).findNeedCompensation(3L, MessageNames.SECKILL_ORDER_CREATE_ROUTING_KEY, 40);
        verify(publisher).resend(message);
    }
}

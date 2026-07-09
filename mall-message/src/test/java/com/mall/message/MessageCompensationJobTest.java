package com.mall.message;

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
class MessageCompensationJobTest {

    @Mock
    private ReliableMessageRepository repository;

    @Mock
    private ReliableMessagePublisher publisher;

    @Test
    void compensateShouldContinueWhenDispatchingTimeoutMarkFails() {
        ReliableMessage message = ReliableMessage.of("mall.exchange", "seckill.order.create", "r1", "{}");
        MessageCompensationJob job = new MessageCompensationJob(repository, publisher, "", 30, 25);
        when(repository.markDispatchingTimedOut(any(Instant.class), eq(25)))
                .thenThrow(new RuntimeException("sharding update limit unsupported"));
        when(repository.findNeedCompensation(25)).thenReturn(List.of(message));

        job.compensate();

        verify(repository).findNeedCompensation(25);
        verify(publisher).resend(message);
    }

    @Test
    void compensateShouldUseConfiguredBatchSizePerBucketShardKey() {
        ReliableMessage message = ReliableMessage.of("mall.exchange", "seckill.order.create", "r1", "{}", 3L, null);
        MessageCompensationJob job = new MessageCompensationJob(repository, publisher, "1,3", 30, 40);
        when(repository.findNeedCompensation(1L, 40)).thenReturn(List.of());
        when(repository.findNeedCompensation(3L, 40)).thenReturn(List.of(message));

        job.compensate();

        verify(repository).markDispatchingTimedOut(any(Instant.class), eq(40));
        verify(repository).findNeedCompensation(1L, 40);
        verify(repository).findNeedCompensation(3L, 40);
        verify(publisher).resend(message);
    }
}

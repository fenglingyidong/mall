package com.mall.message;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReliableMessagePublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private ReliableMessageRepository repository;

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void enqueueShouldSendOnlyAfterTransactionCommit() {
        when(repository.markDispatching(anyString(), nullable(Long.class))).thenReturn(true);
        ReliableMessagePublisher publisher = new ReliableMessagePublisher(rabbitTemplate, repository, new SyncTaskExecutor());
        TransactionSynchronizationManager.initSynchronization();

        ReliableMessage message = publisher.enqueueSeckillOrderCreate("r1", "{\"requestId\":\"r1\"}");

        verify(repository).save(message);
        verify(rabbitTemplate, never()).convertAndSend(
                any(String.class),
                any(String.class),
                any(Object.class),
                any(MessagePostProcessor.class),
                any(CorrelationData.class));

        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);
        synchronizations.get(0).afterCommit();

        ArgumentCaptor<CorrelationData> correlationCaptor = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbitTemplate).convertAndSend(
                eq(MessageNames.MALL_EXCHANGE),
                eq(MessageNames.SECKILL_ORDER_CREATE_ROUTING_KEY),
                eq("{\"requestId\":\"r1\"}"),
                any(MessagePostProcessor.class),
                correlationCaptor.capture());
        assertThat(correlationCaptor.getValue().getId()).isEqualTo(message.messageId());
    }

    @Test
    void enqueueShouldKeepMessageCompensatableWhenDispatchExecutorRejects() {
        ReliableMessagePublisher publisher = new ReliableMessagePublisher(
                rabbitTemplate,
                repository,
                task -> {
                    throw new TaskRejectedException("queue full");
                });

        assertThatCode(() -> publisher.enqueueSeckillOrderCreate("r1", "{\"requestId\":\"r1\"}", 3L))
                .doesNotThrowAnyException();

        verify(repository).save(any(ReliableMessage.class));
        verify(repository, never()).markDispatching(anyString(), nullable(Long.class));
        verify(rabbitTemplate, never()).convertAndSend(
                any(String.class),
                any(String.class),
                any(Object.class),
                any(MessagePostProcessor.class),
                any(CorrelationData.class));
    }

    @Test
    void enqueueShardedSeckillOrderShouldCarryBucketShardKey() {
        when(repository.markDispatching(anyString(), nullable(Long.class))).thenReturn(true);
        ReliableMessagePublisher publisher = new ReliableMessagePublisher(rabbitTemplate, repository, new SyncTaskExecutor());

        ReliableMessage message = publisher.enqueueSeckillOrderCreate("r1", "{\"requestId\":\"r1\"}", 3L);

        assertThat(message.bucketShardKey()).isEqualTo(3L);
        verify(repository).save(message);
        ArgumentCaptor<CorrelationData> correlationCaptor = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbitTemplate).convertAndSend(
                eq(MessageNames.MALL_EXCHANGE),
                eq(MessageNames.SECKILL_ORDER_CREATE_ROUTING_KEY),
                eq("{\"requestId\":\"r1\"}"),
                any(MessagePostProcessor.class),
                correlationCaptor.capture());
        assertThat(correlationCaptor.getValue().getId()).isEqualTo("3@" + message.messageId());
    }

    @Test
    void publishShardedSeckillResultShouldCarryBucketShardKey() {
        when(repository.markDispatching(anyString(), nullable(Long.class))).thenReturn(true);
        ReliableMessagePublisher publisher = new ReliableMessagePublisher(rabbitTemplate, repository, new SyncTaskExecutor());

        ReliableMessage message = publisher.publishSeckillOrderResult("r1", "{\"requestId\":\"r1\"}", 3L);

        assertThat(message.bucketShardKey()).isEqualTo(3L);
        verify(repository).save(message);
        ArgumentCaptor<CorrelationData> correlationCaptor = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbitTemplate).convertAndSend(
                eq(MessageNames.MALL_EXCHANGE),
                eq(MessageNames.SECKILL_ORDER_RESULT_ROUTING_KEY),
                eq("{\"requestId\":\"r1\"}"),
                any(MessagePostProcessor.class),
                correlationCaptor.capture());
        assertThat(correlationCaptor.getValue().getId()).isEqualTo("3@" + message.messageId());
    }
}

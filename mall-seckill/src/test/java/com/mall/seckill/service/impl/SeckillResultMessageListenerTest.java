package com.mall.seckill.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.message.ReliableMessagePublisher;
import com.mall.message.ReliableMessageRepository;
import com.mall.message.SeckillOrderResultMessage;
import com.mall.seckill.cache.SeckillStockCache;
import com.mall.seckill.config.SeckillProperties;
import com.mall.seckill.mapper.SeckillRepository;
import com.mall.seckill.mapper.SeckillResultRetryRepository;
import com.mall.seckill.pojo.vo.SeckillResult;
import com.mall.seckill.pojo.vo.StockReleaseResult;
import com.mall.seckill.pojo.vo.StockVersion;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillResultMessageListenerTest {

    @Mock
    private SeckillRepository repository;

    @Mock
    private SeckillStockCache stockCache;

    @Mock
    private ReliableMessageRepository messageRepository;

    @Mock
    private Channel channel;

    @Mock
    private SeckillResultRetryRepository retryRepository;

    @Mock
    private ReliableMessagePublisher retryMessagePublisher;

    @Test
    void shouldConfirmSnapshotWithoutRefreshingStockOnSuccess() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        SeckillResultMessageListener listener = new SeckillResultMessageListener(repository, stockCache, objectMapper, messageRepository);
        when(repository.confirmDeduction("r1", 3L, "S1", "ok"))
                .thenReturn(new SeckillRepository.StockSnapshot("r1", 1L, 1001L, 101L, 1, "CONFIRMED"));

        listener.onSeckillOrderResult(
                objectMapper.writeValueAsString(new SeckillOrderResultMessage("r1", "SUCCESS", "S1", "ok", 3L)),
                message(),
                channel);

        verify(stockCache, never()).refresh(anyLong(), anyLong(), any());
        ArgumentCaptor<SeckillResult> resultCaptor = ArgumentCaptor.forClass(SeckillResult.class);
        verify(repository).saveResult(resultCaptor.capture());
        assertThat(resultCaptor.getValue()).isEqualTo(new SeckillResult("r1", "SUCCESS", "S1", "ok"));
        verify(messageRepository).markConsumed("m1", 3L);
        verify(channel).basicAck(7L, false);
    }

    @Test
    void shouldReleaseStockAndRefreshCacheOnFailure() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        SeckillResultMessageListener listener = new SeckillResultMessageListener(repository, stockCache, objectMapper, messageRepository);
        SeckillRepository.StockSnapshot snapshot = new SeckillRepository.StockSnapshot("r1", 1L, 1001L, 101L, 1, "RELEASED");
        StockVersion stockVersion = new StockVersion(50, 2L);
        when(repository.releaseDeduction("r1", 3L, "failed"))
                .thenReturn(new StockReleaseResult(snapshot, stockVersion));

        listener.onSeckillOrderResult(
                objectMapper.writeValueAsString(new SeckillOrderResultMessage("r1", "r1", "FAILED", null, "failed", 3L)),
                message(),
                channel);

        verify(repository).releaseDeduction("r1", 3L, "failed");
        verify(stockCache).refresh(1L, 1001L, stockVersion);
        ArgumentCaptor<SeckillResult> resultCaptor = ArgumentCaptor.forClass(SeckillResult.class);
        verify(repository).saveResult(resultCaptor.capture());
        assertThat(resultCaptor.getValue()).isEqualTo(new SeckillResult("r1", "FAILED", null, "failed"));
        verify(messageRepository).markConsumed("m1", 3L);
        verify(channel).basicAck(7L, false);
    }

    @Test
    void shouldRejectSuccessResultWhenSnapshotMissing() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        SeckillResultMessageListener listener = new SeckillResultMessageListener(repository, stockCache, objectMapper, messageRepository);

        listener.onSeckillOrderResult(
                objectMapper.writeValueAsString(new SeckillOrderResultMessage("r1", "r1", "SUCCESS", "S1", "ok", 3L)),
                message(),
                channel);

        verify(repository, never()).saveResult(any());
        verify(stockCache, never()).refresh(anyLong(), anyLong(), any());
        verify(messageRepository, never()).markConsumed("m1", 3L);
        verify(channel).basicNack(7L, false, false);
    }

    @Test
    void shouldRejectFailedResultWhenSnapshotMissing() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        SeckillResultMessageListener listener = new SeckillResultMessageListener(repository, stockCache, objectMapper, messageRepository);

        listener.onSeckillOrderResult(
                objectMapper.writeValueAsString(new SeckillOrderResultMessage("r1", "r1", "FAILED", null, "failed", 3L)),
                message(),
                channel);

        verify(repository).releaseDeduction("r1", 3L, "failed");
        verify(repository, never()).saveResult(any());
        verify(stockCache, never()).refresh(anyLong(), anyLong(), any());
        verify(messageRepository, never()).markConsumed("m1", 3L);
        verify(channel, never()).basicAck(7L, false);
        verify(channel).basicNack(7L, false, false);
    }

    @Test
    void shouldReleaseConfirmedStockOnOrderClosed() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        SeckillResultMessageListener listener = new SeckillResultMessageListener(repository, stockCache, objectMapper, messageRepository);
        SeckillRepository.StockSnapshot snapshot = new SeckillRepository.StockSnapshot("r1", 1L, 1001L, 101L, 1, "RELEASED");
        StockVersion stockVersion = new StockVersion(51, 3L);
        when(repository.releaseConfirmedDeduction("r1", 3L, "closed"))
                .thenReturn(new StockReleaseResult(snapshot, stockVersion));

        listener.onSeckillOrderResult(
                objectMapper.writeValueAsString(new SeckillOrderResultMessage("r1", "r1", "ORDER_CLOSED", null, "closed", 3L)),
                message(),
                channel);

        verify(repository).releaseConfirmedDeduction("r1", 3L, "closed");
        verify(repository, never()).releaseDeduction("r1", 3L, "closed");
        verify(stockCache).refresh(1L, 1001L, stockVersion);
        verify(messageRepository).markConsumed("m1", 3L);
        verify(channel).basicAck(7L, false);
    }

    @Test
    void shouldRejectOrderClosedResultWhenSnapshotIsNotConfirmedYet() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        SeckillResultMessageListener listener = new SeckillResultMessageListener(repository, stockCache, objectMapper, messageRepository);
        SeckillRepository.StockSnapshot snapshot = new SeckillRepository.StockSnapshot("r1", 1L, 1001L, 101L, 1, "DEDUCTED");
        when(repository.releaseConfirmedDeduction("r1", 3L, "closed"))
                .thenReturn(new StockReleaseResult(snapshot, null));

        listener.onSeckillOrderResult(
                objectMapper.writeValueAsString(new SeckillOrderResultMessage("r1", "r1", "ORDER_CLOSED", null, "closed", 3L)),
                message(),
                channel);

        verify(repository).releaseConfirmedDeduction("r1", 3L, "closed");
        verify(repository, never()).saveResult(any());
        verify(stockCache, never()).refresh(anyLong(), anyLong(), any());
        verify(messageRepository, never()).markConsumed("m1", 3L);
        verify(channel, never()).basicAck(7L, false);
        verify(channel).basicNack(7L, false, false);
    }

    @Test
    void shouldTerminalizeSuccessResultWhenRetryExhausted() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        SeckillResultMessageListener listener = retryingListener(objectMapper);
        when(retryRepository.recordFailure(
                eq("m1"),
                eq("r1"),
                eq("SUCCESS"),
                any(String.class),
                eq(3L),
                any(String.class),
                eq(1),
                anyList()))
                .thenReturn(new SeckillResultRetryRepository.RetryDecision(false, 2, 0));
        listener.onSeckillOrderResult(
                objectMapper.writeValueAsString(new SeckillOrderResultMessage("r1", "r1", "SUCCESS", "S1", "ok", 3L)),
                message(),
                channel);

        ArgumentCaptor<SeckillResult> resultCaptor = ArgumentCaptor.forClass(SeckillResult.class);
        verify(repository).saveResult(resultCaptor.capture());
        assertThat(resultCaptor.getValue().requestId()).isEqualTo("r1");
        assertThat(resultCaptor.getValue().status()).isEqualTo("FAILED");
        assertThat(resultCaptor.getValue().message()).contains("Result retry exhausted");
        verify(messageRepository).markConsumed("m1", 3L);
        verify(channel).basicAck(7L, false);
    }

    @Test
    void shouldTerminalizeFailedResultWhenRetryExhausted() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        SeckillResultMessageListener listener = retryingListener(objectMapper);
        when(retryRepository.recordFailure(
                eq("m1"),
                eq("r1"),
                eq("FAILED"),
                any(String.class),
                eq(3L),
                any(String.class),
                eq(1),
                anyList()))
                .thenReturn(new SeckillResultRetryRepository.RetryDecision(false, 2, 0));

        listener.onSeckillOrderResult(
                objectMapper.writeValueAsString(new SeckillOrderResultMessage("r1", "r1", "FAILED", null, "failed", 3L)),
                message(),
                channel);

        ArgumentCaptor<SeckillResult> resultCaptor = ArgumentCaptor.forClass(SeckillResult.class);
        verify(repository).saveResult(resultCaptor.capture());
        assertThat(resultCaptor.getValue().requestId()).isEqualTo("r1");
        assertThat(resultCaptor.getValue().status()).isEqualTo("FAILED");
        assertThat(resultCaptor.getValue().message()).contains("Result retry exhausted");
        verify(messageRepository).markConsumed("m1", 3L);
        verify(channel).basicAck(7L, false);
    }

    private SeckillResultMessageListener retryingListener(ObjectMapper objectMapper) {
        SeckillProperties properties = new SeckillProperties();
        properties.getResultRetry().setEnabled(true);
        properties.getResultRetry().setMaxAttempts(1);
        ObjectProvider<SeckillResultRetryRepository> retryProvider = mock(ObjectProvider.class);
        ObjectProvider<ReliableMessagePublisher> publisherProvider = mock(ObjectProvider.class);
        when(retryProvider.getIfAvailable()).thenReturn(retryRepository);
        when(publisherProvider.getIfAvailable()).thenReturn(retryMessagePublisher);
        return new SeckillResultMessageListener(
                repository,
                stockCache,
                objectMapper,
                messageRepository,
                retryProvider,
                publisherProvider,
                properties);
    }

    private Message message() {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(7L);
        properties.setMessageId("m1");
        return new Message(new byte[0], properties);
    }
}

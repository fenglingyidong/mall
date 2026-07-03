package com.mall.seckill.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.message.ReliableMessageRepository;
import com.mall.message.SeckillOrderResultMessage;
import com.mall.seckill.cache.SeckillStockCache;
import com.mall.seckill.mapper.SeckillRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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

    @Test
    void shouldConfirmSnapshotWithoutRefreshingStockOnSuccess() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        SeckillResultMessageListener listener = new SeckillResultMessageListener(repository, stockCache, objectMapper, messageRepository);
        when(repository.confirmDeduction("r1", "S1", "ok"))
                .thenReturn(new SeckillRepository.StockSnapshot("r1", 1L, 1001L, 101L, 1, "CONFIRMED"));

        listener.onSeckillOrderResult(
                objectMapper.writeValueAsString(new SeckillOrderResultMessage("r1", "SUCCESS", "S1", "ok")),
                message(),
                channel);

        verify(stockCache, never()).refresh(anyLong(), anyLong(), any());
        ArgumentCaptor<SeckillResult> resultCaptor = ArgumentCaptor.forClass(SeckillResult.class);
        verify(repository).saveResult(resultCaptor.capture());
        assertThat(resultCaptor.getValue()).isEqualTo(new SeckillResult("r1", "SUCCESS", "S1", "ok"));
        verify(messageRepository).markConsumed("m1");
        verify(channel).basicAck(7L, false);
    }

    @Test
    void shouldReleaseStockAndRefreshCacheOnFailure() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        SeckillResultMessageListener listener = new SeckillResultMessageListener(repository, stockCache, objectMapper, messageRepository);
        SeckillRepository.StockSnapshot snapshot = new SeckillRepository.StockSnapshot("r1", 1L, 1001L, 101L, 1, "RELEASED");
        StockVersion stockVersion = new StockVersion(50, 2L);
        when(repository.releaseDeduction("r1", "failed"))
                .thenReturn(new StockReleaseResult(snapshot, stockVersion));

        listener.onSeckillOrderResult(
                objectMapper.writeValueAsString(new SeckillOrderResultMessage("r1", "FAILED", null, "failed")),
                message(),
                channel);

        verify(stockCache).refresh(1L, 1001L, stockVersion);
        ArgumentCaptor<SeckillResult> resultCaptor = ArgumentCaptor.forClass(SeckillResult.class);
        verify(repository).saveResult(resultCaptor.capture());
        assertThat(resultCaptor.getValue()).isEqualTo(new SeckillResult("r1", "FAILED", null, "failed"));
        verify(messageRepository).markConsumed("m1");
        verify(channel).basicAck(7L, false);
    }

    private Message message() {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(7L);
        properties.setMessageId("m1");
        return new Message(new byte[0], properties);
    }
}

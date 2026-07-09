package com.mall.seckill.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.mall.message.MessageNames;
import com.mall.message.ReliableMessagePublisher;
import com.mall.message.ReliableMessageRepository;
import com.mall.seckill.config.SeckillProperties;
import com.mall.seckill.mapper.SeckillRepository;
import com.mall.seckill.mapper.SeckillStockChangeLogMapper;
import com.mall.seckill.pojo.entity.SeckillSku;
import com.mall.seckill.pojo.entity.SeckillStockChangeLogEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillOrderOutboxFromChangeLogServiceTest {

    @Mock
    private SeckillStockChangeLogMapper changeLogMapper;

    @Mock
    private SeckillRepository seckillRepository;

    @Mock
    private ReliableMessageRepository messageRepository;

    @Mock
    private ReliableMessagePublisher messagePublisher;

    private SeckillProperties properties;
    private SeckillOrderOutboxFromChangeLogService service;

    @BeforeEach
    void setUp() {
        properties = new SeckillProperties();
        properties.getOrderOutbox().setEnabled(true);
        service = new SeckillOrderOutboxFromChangeLogService(
                changeLogMapper,
                seckillRepository,
                messageRepository,
                messagePublisher,
                new ObjectMapper(),
                properties);
    }

    @Test
    void shouldEnqueueOrderCreateAndMarkOutboxedForDeductChangeLog() throws Exception {
        SeckillStockChangeLogEntity changeLog = deductChangeLog();
        when(changeLogMapper.selectByStatusForConsume(SeckillStockChangeLogStatus.NEW, 500))
                .thenReturn(List.of(changeLog));
        when(changeLogMapper.updateStatusByShard(
                11L, 7L, SeckillStockChangeLogStatus.NEW, SeckillStockChangeLogStatus.OUTBOXING))
                .thenReturn(1);
        when(messageRepository.existsByBusinessKeyAndRoutingKey(
                "req-1", MessageNames.SECKILL_ORDER_CREATE_ROUTING_KEY, 7L))
                .thenReturn(false);
        when(seckillRepository.findStockSnapshot("req-1", 7L))
                .thenReturn(new SeckillRepository.StockSnapshot("req-1", 1L, 1001L, 2001L, 2, "DEDUCTED"));
        when(seckillRepository.requireSku(1L, 1001L))
                .thenReturn(new SeckillSku(10L, 1L, 1001L, "phone", BigDecimal.valueOf(99), 50));
        when(changeLogMapper.updateStatusByShard(
                11L, 7L, SeckillStockChangeLogStatus.OUTBOXING, SeckillStockChangeLogStatus.OUTBOXED))
                .thenReturn(1);

        int drained = service.drainOnce();

        assertThat(drained).isEqualTo(1);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(messagePublisher).enqueueSeckillOrderCreate(eq("req-1"), payloadCaptor.capture(), eq(7L));
        JsonNode payload = new ObjectMapper().readTree(payloadCaptor.getValue());
        assertThat(payload.get("requestId").asText()).isEqualTo("req-1");
        assertThat(payload.get("activityId").asLong()).isEqualTo(1L);
        assertThat(payload.get("userId").asLong()).isEqualTo(2001L);
        assertThat(payload.get("skuId").asLong()).isEqualTo(1001L);
        assertThat(payload.get("skuName").asText()).isEqualTo("phone");
        assertThat(payload.get("price").decimalValue()).isEqualByComparingTo(BigDecimal.valueOf(99));
        assertThat(payload.get("quantity").asInt()).isEqualTo(2);
        assertThat(payload.get("bucketShardKey").asLong()).isEqualTo(7L);
        verify(changeLogMapper).updateStatusByShard(
                11L, 7L, SeckillStockChangeLogStatus.OUTBOXING, SeckillStockChangeLogStatus.OUTBOXED);
    }

    @Test
    void shouldMarkOutboxedWithoutEnqueueWhenOutboxAlreadyExists() {
        SeckillStockChangeLogEntity changeLog = deductChangeLog();
        when(changeLogMapper.selectByStatusForConsume(SeckillStockChangeLogStatus.NEW, 500))
                .thenReturn(List.of(changeLog));
        when(changeLogMapper.updateStatusByShard(
                11L, 7L, SeckillStockChangeLogStatus.NEW, SeckillStockChangeLogStatus.OUTBOXING))
                .thenReturn(1);
        when(messageRepository.existsByBusinessKeyAndRoutingKey(
                "req-1", MessageNames.SECKILL_ORDER_CREATE_ROUTING_KEY, 7L))
                .thenReturn(true);
        when(changeLogMapper.updateStatusByShard(
                11L, 7L, SeckillStockChangeLogStatus.OUTBOXING, SeckillStockChangeLogStatus.OUTBOXED))
                .thenReturn(1);

        int drained = service.drainOnce();

        assertThat(drained).isEqualTo(1);
        verify(messagePublisher, never()).enqueueSeckillOrderCreate(anyString(), anyString(), eq(7L));
        verifyNoInteractions(seckillRepository);
    }

    @Test
    void shouldMarkNonDeductChangeLogOutboxedWithoutEnqueue() {
        SeckillStockChangeLogEntity changeLog = deductChangeLog();
        changeLog.setChangeType("RELEASE");
        when(changeLogMapper.selectByStatusForConsume(SeckillStockChangeLogStatus.NEW, 500))
                .thenReturn(List.of(changeLog));
        when(changeLogMapper.updateStatusByShard(
                11L, 7L, SeckillStockChangeLogStatus.NEW, SeckillStockChangeLogStatus.OUTBOXING))
                .thenReturn(1);
        when(changeLogMapper.updateStatusByShard(
                11L, 7L, SeckillStockChangeLogStatus.OUTBOXING, SeckillStockChangeLogStatus.OUTBOXED))
                .thenReturn(1);

        int drained = service.drainOnce();

        assertThat(drained).isEqualTo(1);
        verify(messagePublisher, never()).enqueueSeckillOrderCreate(anyString(), anyString(), eq(7L));
        verifyNoInteractions(seckillRepository, messageRepository);
    }

    @Test
    void shouldMarkOutboxFailedAndContinueWhenLookupThrows() {
        SeckillStockChangeLogEntity changeLog = deductChangeLog();
        when(changeLogMapper.selectByStatusForConsume(SeckillStockChangeLogStatus.NEW, 500))
                .thenReturn(List.of(changeLog));
        when(changeLogMapper.updateStatusByShard(
                11L, 7L, SeckillStockChangeLogStatus.NEW, SeckillStockChangeLogStatus.OUTBOXING))
                .thenReturn(1);
        when(messageRepository.existsByBusinessKeyAndRoutingKey(
                "req-1", MessageNames.SECKILL_ORDER_CREATE_ROUTING_KEY, 7L))
                .thenReturn(false);
        when(seckillRepository.findStockSnapshot("req-1", 7L))
                .thenThrow(new IllegalStateException("snapshot lookup failed"));

        assertThatCode(() -> assertThat(service.drainOnce()).isZero())
                .doesNotThrowAnyException();
        verify(changeLogMapper).updateStatusByShard(
                11L, 7L, SeckillStockChangeLogStatus.OUTBOXING, SeckillStockChangeLogStatus.OUTBOX_FAILED);
        verify(messagePublisher, never()).enqueueSeckillOrderCreate(anyString(), anyString(), eq(7L));
    }

    @Test
    void shouldReturnZeroAndSkipQueryWhenDisabled() {
        properties.getOrderOutbox().setEnabled(false);

        int drained = service.drainOnce();

        assertThat(drained).isZero();
        verifyNoInteractions(changeLogMapper, seckillRepository, messageRepository, messagePublisher);
    }

    private SeckillStockChangeLogEntity deductChangeLog() {
        SeckillStockChangeLogEntity changeLog = new SeckillStockChangeLogEntity();
        changeLog.setId(11L);
        changeLog.setRequestId("req-1");
        changeLog.setActivityId(1L);
        changeLog.setSkuId(1001L);
        changeLog.setBucketShardKey(7L);
        changeLog.setChangeType("DEDUCT");
        changeLog.setQuantityDelta(-2);
        changeLog.setStatus(SeckillStockChangeLogStatus.NEW);
        return changeLog;
    }
}

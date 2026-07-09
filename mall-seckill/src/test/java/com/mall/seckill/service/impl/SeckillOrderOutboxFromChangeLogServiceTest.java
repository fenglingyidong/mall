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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
    private TestTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        properties = new SeckillProperties();
        properties.getOrderOutbox().setEnabled(true);
        transactionManager = new TestTransactionManager();
        service = new SeckillOrderOutboxFromChangeLogService(
                changeLogMapper,
                seckillRepository,
                messageRepository,
                messagePublisher,
                new ObjectMapper(),
                new TransactionTemplate(transactionManager),
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
        assertThat(transactionManager.committed()).isEqualTo(1);
        assertThat(transactionManager.rolledBack()).isZero();
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
    void shouldMarkOutboxFailedWhenOutboxedUpdateReturnsZeroAfterEnqueue() {
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
                .thenReturn(0);

        assertThatCode(() -> assertThat(service.drainOnce()).isZero())
                .doesNotThrowAnyException();

        assertThat(transactionManager.committed()).isZero();
        assertThat(transactionManager.rolledBack()).isEqualTo(1);
        verify(messagePublisher).enqueueSeckillOrderCreate(eq("req-1"), anyString(), eq(7L));
        verify(changeLogMapper).updateStatusByShard(
                11L, 7L, SeckillStockChangeLogStatus.OUTBOXING, SeckillStockChangeLogStatus.OUTBOX_FAILED);
    }

    @Test
    void shouldContinueBatchAfterFirstOutboxedUpdateThrows() {
        SeckillStockChangeLogEntity first = deductChangeLog(11L, "req-1", 7L);
        SeckillStockChangeLogEntity second = deductChangeLog(12L, "req-2", 8L);
        when(changeLogMapper.selectByStatusForConsume(SeckillStockChangeLogStatus.NEW, 500))
                .thenReturn(List.of(first, second));
        when(changeLogMapper.updateStatusByShard(
                11L, 7L, SeckillStockChangeLogStatus.NEW, SeckillStockChangeLogStatus.OUTBOXING))
                .thenReturn(1);
        when(changeLogMapper.updateStatusByShard(
                12L, 8L, SeckillStockChangeLogStatus.NEW, SeckillStockChangeLogStatus.OUTBOXING))
                .thenReturn(1);
        when(messageRepository.existsByBusinessKeyAndRoutingKey(
                "req-1", MessageNames.SECKILL_ORDER_CREATE_ROUTING_KEY, 7L))
                .thenReturn(false);
        when(messageRepository.existsByBusinessKeyAndRoutingKey(
                "req-2", MessageNames.SECKILL_ORDER_CREATE_ROUTING_KEY, 8L))
                .thenReturn(false);
        when(seckillRepository.findStockSnapshot("req-1", 7L))
                .thenReturn(new SeckillRepository.StockSnapshot("req-1", 1L, 1001L, 2001L, 2, "DEDUCTED"));
        when(seckillRepository.findStockSnapshot("req-2", 8L))
                .thenReturn(new SeckillRepository.StockSnapshot("req-2", 1L, 1001L, 2002L, 1, "DEDUCTED"));
        when(seckillRepository.requireSku(1L, 1001L))
                .thenReturn(new SeckillSku(10L, 1L, 1001L, "phone", BigDecimal.valueOf(99), 50));
        when(changeLogMapper.updateStatusByShard(
                11L, 7L, SeckillStockChangeLogStatus.OUTBOXING, SeckillStockChangeLogStatus.OUTBOXED))
                .thenThrow(new IllegalStateException("outboxed update failed"));
        when(changeLogMapper.updateStatusByShard(
                12L, 8L, SeckillStockChangeLogStatus.OUTBOXING, SeckillStockChangeLogStatus.OUTBOXED))
                .thenReturn(1);

        int drained = service.drainOnce();

        assertThat(drained).isEqualTo(1);
        verify(changeLogMapper).updateStatusByShard(
                11L, 7L, SeckillStockChangeLogStatus.OUTBOXING, SeckillStockChangeLogStatus.OUTBOX_FAILED);
        verify(changeLogMapper).updateStatusByShard(
                12L, 8L, SeckillStockChangeLogStatus.OUTBOXING, SeckillStockChangeLogStatus.OUTBOXED);
        verify(messagePublisher).enqueueSeckillOrderCreate(eq("req-1"), anyString(), eq(7L));
        verify(messagePublisher).enqueueSeckillOrderCreate(eq("req-2"), anyString(), eq(8L));
    }

    @Test
    void shouldSkipWhenClaimFailsWithoutEnqueueOrMarkFailed() {
        SeckillStockChangeLogEntity changeLog = deductChangeLog();
        when(changeLogMapper.selectByStatusForConsume(SeckillStockChangeLogStatus.NEW, 500))
                .thenReturn(List.of(changeLog));
        when(changeLogMapper.updateStatusByShard(
                11L, 7L, SeckillStockChangeLogStatus.NEW, SeckillStockChangeLogStatus.OUTBOXING))
                .thenReturn(0);

        int drained = service.drainOnce();

        assertThat(drained).isZero();
        verifyNoInteractions(seckillRepository, messageRepository, messagePublisher);
        verify(changeLogMapper).selectByStatusForConsume(SeckillStockChangeLogStatus.NEW, 500);
        verify(changeLogMapper).updateStatusByShard(
                11L, 7L, SeckillStockChangeLogStatus.NEW, SeckillStockChangeLogStatus.OUTBOXING);
        verifyNoMoreInteractions(changeLogMapper);
    }

    @Test
    void shouldClampBatchSizeToOneWhenConfiguredBelowMinimum() {
        properties.getOrderOutbox().setBatchSize(0);
        when(changeLogMapper.selectByStatusForConsume(SeckillStockChangeLogStatus.NEW, 1))
                .thenReturn(List.of());

        int drained = service.drainOnce();

        assertThat(drained).isZero();
        verify(changeLogMapper).selectByStatusForConsume(SeckillStockChangeLogStatus.NEW, 1);
    }

    @Test
    void shouldClampBatchSizeToOneThousandWhenConfiguredAboveMaximum() {
        properties.getOrderOutbox().setBatchSize(1001);
        when(changeLogMapper.selectByStatusForConsume(SeckillStockChangeLogStatus.NEW, 1000))
                .thenReturn(List.of());

        int drained = service.drainOnce();

        assertThat(drained).isZero();
        verify(changeLogMapper).selectByStatusForConsume(SeckillStockChangeLogStatus.NEW, 1000);
    }

    @Test
    void shouldReturnZeroAndSkipQueryWhenDisabled() {
        properties.getOrderOutbox().setEnabled(false);

        int drained = service.drainOnce();

        assertThat(drained).isZero();
        verifyNoInteractions(changeLogMapper, seckillRepository, messageRepository, messagePublisher);
    }

    private SeckillStockChangeLogEntity deductChangeLog() {
        return deductChangeLog(11L, "req-1", 7L);
    }

    private SeckillStockChangeLogEntity deductChangeLog(Long id, String requestId, Long bucketShardKey) {
        SeckillStockChangeLogEntity changeLog = new SeckillStockChangeLogEntity();
        changeLog.setId(id);
        changeLog.setRequestId(requestId);
        changeLog.setActivityId(1L);
        changeLog.setSkuId(1001L);
        changeLog.setBucketShardKey(bucketShardKey);
        changeLog.setChangeType("DEDUCT");
        changeLog.setQuantityDelta(-2);
        changeLog.setStatus(SeckillStockChangeLogStatus.NEW);
        return changeLog;
    }

    private static class TestTransactionManager implements PlatformTransactionManager {

        private int committed;
        private int rolledBack;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            committed++;
        }

        @Override
        public void rollback(TransactionStatus status) {
            rolledBack++;
        }

        int committed() {
            return committed;
        }

        int rolledBack() {
            return rolledBack;
        }
    }
}

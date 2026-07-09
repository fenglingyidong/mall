package com.mall.seckill.service.impl;

import com.mall.seckill.config.SeckillProperties;
import com.mall.seckill.mapper.SeckillStockChangeLogMapper;
import com.mall.seckill.pojo.entity.SeckillStockChangeLogEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillCenterBucketLedgerConsumerTest {

    @Mock
    private SeckillStockChangeLogMapper changeLogMapper;

    @Mock
    private SeckillCenterBucketLedgerApplier applier;

    private SeckillProperties properties;
    private SeckillCenterBucketLedgerConsumer consumer;

    @BeforeEach
    void setUp() {
        properties = new SeckillProperties();
        properties.getBucket().getCenterLedger().setBatchSize(2);
        consumer = new SeckillCenterBucketLedgerConsumer(changeLogMapper, applier, properties);
    }

    @Test
    void shouldConsumeNewChangeLogsWithConfiguredBatchSize() {
        SeckillStockChangeLogEntity first = changeLog(1L, 1001L);
        SeckillStockChangeLogEntity second = changeLog(2L, 1001L);
        when(changeLogMapper.selectByStatusForConsume(SeckillStockChangeLogStatus.NEW, 2))
                .thenReturn(List.of(first, second));

        consumer.consume();

        verify(applier).apply(List.of(first, second));
    }

    @Test
    void shouldConsumeOutboxedChangeLogsWhenOrderOutboxEnabled() {
        properties.getOrderOutbox().setEnabled(true);
        SeckillStockChangeLogEntity changeLog = changeLog(1L, 1001L);
        when(changeLogMapper.selectByStatusForConsume(SeckillStockChangeLogStatus.OUTBOXED, 2))
                .thenReturn(List.of(changeLog));

        consumer.consume();

        verify(applier).apply(List.of(changeLog));
    }

    @Test
    void shouldConsumeNewChangeLogsByShardWhenOrderOutboxDisabled() {
        properties.getBucket().getRouting().setBucketShardKeys(List.of(3L));
        SeckillStockChangeLogEntity changeLog = changeLog(1L, 1001L);
        when(changeLogMapper.selectByStatusForConsumeByShard(3L, SeckillStockChangeLogStatus.NEW, 2))
                .thenReturn(List.of(changeLog));

        consumer.consume();

        verify(applier).apply(List.of(changeLog));
    }

    @Test
    void shouldConsumeOutboxedChangeLogsByShardWhenOrderOutboxEnabled() {
        properties.getOrderOutbox().setEnabled(true);
        properties.getBucket().getRouting().setBucketShardKeys(List.of(3L, 5L));
        SeckillStockChangeLogEntity first = changeLog(1L, 1001L);
        SeckillStockChangeLogEntity second = changeLog(2L, 1002L);
        when(changeLogMapper.selectByStatusForConsumeByShard(3L, SeckillStockChangeLogStatus.OUTBOXED, 2))
                .thenReturn(List.of(first));
        when(changeLogMapper.selectByStatusForConsumeByShard(5L, SeckillStockChangeLogStatus.OUTBOXED, 2))
                .thenReturn(List.of(second));

        consumer.consume();

        verify(applier).apply(List.of(first));
        verify(applier).apply(List.of(second));
    }

    @Test
    void shouldContinueWhenOneLedgerGroupFails() {
        SeckillStockChangeLogEntity first = changeLog(1L, 1001L);
        SeckillStockChangeLogEntity second = changeLog(2L, 1002L);
        when(changeLogMapper.selectByStatusForConsume(SeckillStockChangeLogStatus.NEW, 2))
                .thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("apply failed"))
                .when(applier).apply(List.of(first));

        consumer.consume();

        verify(applier).apply(List.of(first));
        verify(applier).apply(List.of(second));
    }

    @Test
    void shouldClampBatchSizeToPositiveValue() {
        properties.getBucket().getCenterLedger().setBatchSize(0);
        when(changeLogMapper.selectByStatusForConsume(SeckillStockChangeLogStatus.NEW, 1))
                .thenReturn(List.of());

        consumer.consume();

        verify(changeLogMapper).selectByStatusForConsume(SeckillStockChangeLogStatus.NEW, 1);
    }

    private SeckillStockChangeLogEntity changeLog(Long id, Long skuId) {
        SeckillStockChangeLogEntity changeLog = new SeckillStockChangeLogEntity();
        changeLog.setId(id);
        changeLog.setActivityId(1L);
        changeLog.setSkuId(skuId);
        return changeLog;
    }
}

package com.mall.seckill.service.impl;

import com.mall.common.exception.BusinessException;
import com.mall.seckill.mapper.SeckillStockBucketMapper;
import com.mall.seckill.mapper.SeckillStockChangeLogMapper;
import com.mall.seckill.pojo.entity.SeckillStockChangeLogEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillCenterBucketLedgerApplierTest {

    @Mock
    private SeckillStockChangeLogMapper changeLogMapper;

    @Mock
    private SeckillStockBucketMapper bucketMapper;

    private SeckillCenterBucketLedgerApplier applier;

    @BeforeEach
    void setUp() {
        applier = new SeckillCenterBucketLedgerApplier(changeLogMapper, bucketMapper);
    }

    @Test
    void shouldApplyDeductChangeLogToCenterBucketAndMarkApplied() {
        SeckillStockChangeLogEntity changeLog = changeLog(1L, -1, SeckillCenterBucketLedgerApplier.STATUS_NEW);
        when(changeLogMapper.selectById(1L)).thenReturn(changeLog);
        when(changeLogMapper.updateStatus(1L,
                SeckillCenterBucketLedgerApplier.STATUS_NEW,
                SeckillCenterBucketLedgerApplier.STATUS_PROCESSING)).thenReturn(1);
        when(bucketMapper.applyCenterQuantityDelta(1L, 1001L, -1)).thenReturn(1);
        when(changeLogMapper.updateStatusByIds(List.of(1L),
                SeckillCenterBucketLedgerApplier.STATUS_PROCESSING,
                SeckillCenterBucketLedgerApplier.STATUS_APPLIED)).thenReturn(1);

        boolean applied = applier.apply(1L);

        assertThat(applied).isTrue();
        InOrder order = inOrder(changeLogMapper, bucketMapper);
        order.verify(changeLogMapper).updateStatus(1L,
                SeckillCenterBucketLedgerApplier.STATUS_NEW,
                SeckillCenterBucketLedgerApplier.STATUS_PROCESSING);
        order.verify(bucketMapper).applyCenterQuantityDelta(1L, 1001L, -1);
        order.verify(changeLogMapper).updateStatusByIds(List.of(1L),
                SeckillCenterBucketLedgerApplier.STATUS_PROCESSING,
                SeckillCenterBucketLedgerApplier.STATUS_APPLIED);
    }

    @Test
    void shouldApplyReleaseChangeLogToCenterBucketAndMarkApplied() {
        SeckillStockChangeLogEntity changeLog = changeLog(2L, 1, SeckillCenterBucketLedgerApplier.STATUS_NEW);
        when(changeLogMapper.selectById(2L)).thenReturn(changeLog);
        when(changeLogMapper.updateStatus(2L,
                SeckillCenterBucketLedgerApplier.STATUS_NEW,
                SeckillCenterBucketLedgerApplier.STATUS_PROCESSING)).thenReturn(1);
        when(bucketMapper.applyCenterQuantityDelta(1L, 1001L, 1)).thenReturn(1);
        when(changeLogMapper.updateStatusByIds(List.of(2L),
                SeckillCenterBucketLedgerApplier.STATUS_PROCESSING,
                SeckillCenterBucketLedgerApplier.STATUS_APPLIED)).thenReturn(1);

        boolean applied = applier.apply(2L);

        assertThat(applied).isTrue();
        verify(bucketMapper).applyCenterQuantityDelta(1L, 1001L, 1);
    }

    @Test
    void shouldAggregateSameSkuChangeLogsBeforeApplyingCenterBucket() {
        SeckillStockChangeLogEntity first = changeLog(1L, -1, SeckillCenterBucketLedgerApplier.STATUS_NEW);
        SeckillStockChangeLogEntity second = changeLog(2L, -2, SeckillCenterBucketLedgerApplier.STATUS_NEW);
        when(changeLogMapper.updateStatus(1L,
                SeckillCenterBucketLedgerApplier.STATUS_NEW,
                SeckillCenterBucketLedgerApplier.STATUS_PROCESSING)).thenReturn(1);
        when(changeLogMapper.updateStatus(2L,
                SeckillCenterBucketLedgerApplier.STATUS_NEW,
                SeckillCenterBucketLedgerApplier.STATUS_PROCESSING)).thenReturn(1);
        when(bucketMapper.applyCenterQuantityDelta(1L, 1001L, -3)).thenReturn(1);
        when(changeLogMapper.updateStatusByIds(List.of(1L, 2L),
                SeckillCenterBucketLedgerApplier.STATUS_PROCESSING,
                SeckillCenterBucketLedgerApplier.STATUS_APPLIED)).thenReturn(2);

        int applied = applier.apply(List.of(first, second));

        assertThat(applied).isEqualTo(2);
        verify(bucketMapper).applyCenterQuantityDelta(1L, 1001L, -3);
        verify(bucketMapper, never()).applyCenterQuantityDelta(1L, 1001L, -1);
        verify(bucketMapper, never()).applyCenterQuantityDelta(1L, 1001L, -2);
    }

    @Test
    void shouldMarkZeroNetDeltaAppliedWithoutTouchingCenterBucket() {
        SeckillStockChangeLogEntity first = changeLog(1L, -1, SeckillCenterBucketLedgerApplier.STATUS_NEW);
        SeckillStockChangeLogEntity second = changeLog(2L, 1, SeckillCenterBucketLedgerApplier.STATUS_NEW);
        when(changeLogMapper.updateStatus(1L,
                SeckillCenterBucketLedgerApplier.STATUS_NEW,
                SeckillCenterBucketLedgerApplier.STATUS_PROCESSING)).thenReturn(1);
        when(changeLogMapper.updateStatus(2L,
                SeckillCenterBucketLedgerApplier.STATUS_NEW,
                SeckillCenterBucketLedgerApplier.STATUS_PROCESSING)).thenReturn(1);
        when(changeLogMapper.updateStatusByIds(List.of(1L, 2L),
                SeckillCenterBucketLedgerApplier.STATUS_PROCESSING,
                SeckillCenterBucketLedgerApplier.STATUS_APPLIED)).thenReturn(2);

        int applied = applier.apply(List.of(first, second));

        assertThat(applied).isEqualTo(2);
        verify(bucketMapper, never()).applyCenterQuantityDelta(1L, 1001L, 0);
    }

    @Test
    void shouldSkipWhenChangeLogAlreadyHandled() {
        when(changeLogMapper.selectById(1L)).thenReturn(changeLog(1L, -1, SeckillCenterBucketLedgerApplier.STATUS_APPLIED));

        boolean applied = applier.apply(1L);

        assertThat(applied).isFalse();
        verify(bucketMapper, never()).applyCenterQuantityDelta(1L, 1001L, -1);
    }

    @Test
    void shouldSkipWhenClaimLosesRace() {
        when(changeLogMapper.selectById(1L)).thenReturn(changeLog(1L, -1, SeckillCenterBucketLedgerApplier.STATUS_NEW));
        when(changeLogMapper.updateStatus(1L,
                SeckillCenterBucketLedgerApplier.STATUS_NEW,
                SeckillCenterBucketLedgerApplier.STATUS_PROCESSING)).thenReturn(0);

        boolean applied = applier.apply(1L);

        assertThat(applied).isFalse();
        verify(bucketMapper, never()).applyCenterQuantityDelta(1L, 1001L, -1);
    }

    @Test
    void shouldThrowWhenCenterBucketCannotApplyDelta() {
        when(changeLogMapper.selectById(1L)).thenReturn(changeLog(1L, -1, SeckillCenterBucketLedgerApplier.STATUS_NEW));
        when(changeLogMapper.updateStatus(1L,
                SeckillCenterBucketLedgerApplier.STATUS_NEW,
                SeckillCenterBucketLedgerApplier.STATUS_PROCESSING)).thenReturn(1);
        when(bucketMapper.applyCenterQuantityDelta(1L, 1001L, -1)).thenReturn(0);

        assertThatThrownBy(() -> applier.apply(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Seckill center bucket ledger apply failed");

        verify(changeLogMapper, never()).updateStatusByIds(List.of(1L),
                SeckillCenterBucketLedgerApplier.STATUS_PROCESSING,
                SeckillCenterBucketLedgerApplier.STATUS_APPLIED);
    }

    @Test
    void shouldThrowWhenAppliedStatusUpdateDoesNotCoverClaimedLogs() {
        when(changeLogMapper.selectById(1L)).thenReturn(changeLog(1L, -1, SeckillCenterBucketLedgerApplier.STATUS_NEW));
        when(changeLogMapper.updateStatus(1L,
                SeckillCenterBucketLedgerApplier.STATUS_NEW,
                SeckillCenterBucketLedgerApplier.STATUS_PROCESSING)).thenReturn(1);
        when(bucketMapper.applyCenterQuantityDelta(1L, 1001L, -1)).thenReturn(1);
        when(changeLogMapper.updateStatusByIds(List.of(1L),
                SeckillCenterBucketLedgerApplier.STATUS_PROCESSING,
                SeckillCenterBucketLedgerApplier.STATUS_APPLIED)).thenReturn(0);

        assertThatThrownBy(() -> applier.apply(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Seckill stock change log status apply failed");
    }

    private SeckillStockChangeLogEntity changeLog(Long id, int quantityDelta, String status) {
        SeckillStockChangeLogEntity changeLog = new SeckillStockChangeLogEntity();
        changeLog.setId(id);
        changeLog.setActivityId(1L);
        changeLog.setSkuId(1001L);
        changeLog.setQuantityDelta(quantityDelta);
        changeLog.setStatus(status);
        return changeLog;
    }
}

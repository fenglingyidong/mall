package com.mall.seckill.service.impl;

import com.mall.common.exception.BusinessException;
import com.mall.seckill.config.SeckillProperties;
import com.mall.seckill.mapper.SeckillBucketConfigMapper;
import com.mall.seckill.mapper.SeckillStockBucketMapper;
import com.mall.seckill.mapper.SeckillStockChangeLogMapper;
import com.mall.seckill.pojo.entity.SeckillBucketConfigEntity;
import com.mall.seckill.pojo.entity.SeckillStockBucketEntity;
import com.mall.seckill.pojo.entity.SeckillStockChangeLogEntity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillBucketAutoTransferServiceTest {

    @Mock
    private SeckillStockBucketMapper bucketMapper;

    @Mock
    private SeckillBucketConfigMapper configMapper;

    @Mock
    private SeckillStockChangeLogMapper changeLogMapper;

    @Mock
    private SeckillBucketAvailabilityCoordinator availabilityCoordinator;

    private SeckillProperties properties;
    private SeckillBucketAutoTransferService service;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        properties = new SeckillProperties();
        properties.getBucket().getAutoTransfer().setEnabled(true);
        properties.getBucket().getAutoTransfer().setTransferSize(8);
        properties.getBucket().getAutoTransfer().setSourceReserveQuantity(1);
        meterRegistry = new SimpleMeterRegistry();
        service = new SeckillBucketAutoTransferService(
                bucketMapper,
                configMapper,
                changeLogMapper,
                properties,
                meterRegistry,
                availabilityCoordinator);
}
    @Test
    void shouldTransferFromSourceToTargetAndWritePairedChangeLogs() {
        SeckillBucketConfigEntity config = config();
        SeckillStockBucketEntity target = bucket(99L, 3, 0);
        SeckillStockBucketEntity source = bucket(10L, 2, 20);
        when(bucketMapper.selectAutoTransferTarget(1L, 1001L, 0)).thenReturn(target);
        when(bucketMapper.selectAutoTransferSource(1L, 1001L, 99L, 8, 1)).thenReturn(source);
        when(bucketMapper.deductTransferSource(10L, 2L, 8, 1)).thenReturn(1);
        when(bucketMapper.addTransferTarget(99L, 3L, 8)).thenReturn(1);
        when(bucketMapper.selectById(10L)).thenReturn(bucket(10L, 2, 12));
        when(bucketMapper.selectById(99L)).thenReturn(bucket(99L, 3, 8));

        SeckillBucketAutoTransferService.AutoTransferResult result = service.transferOnce(config);

        assertThat(result.transferred()).isTrue();
        assertThat(result.quantity()).isEqualTo(8);
        assertThat(meterRegistry.get("seckill.bucket.auto.transfer.attempt").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("seckill.bucket.auto.transfer.success").counter().count()).isEqualTo(1.0);
        verify(availabilityCoordinator).signalPossiblyAvailable(1L, 1001L, 99L, 3, 3L);
        verify(configMapper, never()).addSurvivorBucket(1L, 1001L, 3);
        ArgumentCaptor<SeckillStockChangeLogEntity> changeCaptor = ArgumentCaptor.forClass(SeckillStockChangeLogEntity.class);
        verify(changeLogMapper, org.mockito.Mockito.times(2)).insert(changeCaptor.capture());
        List<SeckillStockChangeLogEntity> changes = changeCaptor.getAllValues();
        assertThat(changes).extracting(SeckillStockChangeLogEntity::getChangeType)
                .containsExactly(
                        SeckillBucketTransferService.CHANGE_TRANSFER_OUT,
                        SeckillBucketTransferService.CHANGE_TRANSFER_IN);
        assertThat(changes).extracting(SeckillStockChangeLogEntity::getQuantityDelta)
                .containsExactly(-8, 8);
        assertThat(changes).extracting(SeckillStockChangeLogEntity::getBucketShardKey)
                .containsExactly(2L, 3L);
        assertThat(changes).extracting(SeckillStockChangeLogEntity::getStatus)
                .containsExactly("NEW", "NEW");
        assertThat(changes.get(0).getRequestId()).isEqualTo(changes.get(1).getRequestId());
    }

    @Test
    void shouldSkipWhenAutoTransferIsDisabled() {
        properties.getBucket().getAutoTransfer().setEnabled(false);

        SeckillBucketAutoTransferService.AutoTransferResult result = service.transferOnce(config());

        assertThat(result.outcome()).isEqualTo(SeckillBucketAutoTransferService.AutoTransferOutcome.DISABLED);
        assertThat(meterRegistry.get("seckill.bucket.auto.transfer.disabled").counter().count()).isEqualTo(1.0);
        verify(bucketMapper, never()).selectAutoTransferTarget(1L, 1001L, 0);
    }

    @Test
    void shouldSkipWhenNoTargetBucketExists() {
        when(bucketMapper.selectAutoTransferTarget(1L, 1001L, 0)).thenReturn(null);

        SeckillBucketAutoTransferService.AutoTransferResult result = service.transferOnce(config());

        assertThat(result.outcome()).isEqualTo(SeckillBucketAutoTransferService.AutoTransferOutcome.TARGET_MISS);
        assertThat(meterRegistry.get("seckill.bucket.auto.transfer.target.miss").counter().count()).isEqualTo(1.0);
        verify(bucketMapper, never()).selectAutoTransferSource(1L, 1001L, 99L, 8, 1);
    }

    @Test
    void shouldSkipWhenNoSourceBucketExists() {
        SeckillStockBucketEntity target = bucket(99L, 3, 0);
        when(bucketMapper.selectAutoTransferTarget(1L, 1001L, 0)).thenReturn(target);
        when(bucketMapper.selectAutoTransferSource(1L, 1001L, 99L, 8, 1)).thenReturn(null);

        SeckillBucketAutoTransferService.AutoTransferResult result = service.transferOnce(config());

        assertThat(result.outcome()).isEqualTo(SeckillBucketAutoTransferService.AutoTransferOutcome.SOURCE_MISS);
        assertThat(meterRegistry.get("seckill.bucket.auto.transfer.source.miss").counter().count()).isEqualTo(1.0);
        verify(bucketMapper, never()).addTransferTarget(99L, 3L, 8);
        verify(changeLogMapper, never()).insert(org.mockito.Mockito.any(SeckillStockChangeLogEntity.class));
    }

    @Test
    void shouldSkipWhenSourceDeductLosesRace() {
        SeckillStockBucketEntity target = bucket(99L, 3, 0);
        SeckillStockBucketEntity source = bucket(10L, 2, 20);
        when(bucketMapper.selectAutoTransferTarget(1L, 1001L, 0)).thenReturn(target);
        when(bucketMapper.selectAutoTransferSource(1L, 1001L, 99L, 8, 1)).thenReturn(source);
        when(bucketMapper.deductTransferSource(10L, 2L, 8, 1)).thenReturn(0);

        SeckillBucketAutoTransferService.AutoTransferResult result = service.transferOnce(config());

        assertThat(result.outcome()).isEqualTo(SeckillBucketAutoTransferService.AutoTransferOutcome.SOURCE_DEDUCT_LOST);
        assertThat(meterRegistry.get("seckill.bucket.auto.transfer.source.deduct.lost").counter().count()).isEqualTo(1.0);
        verify(bucketMapper, never()).addTransferTarget(99L, 3L, 8);
        verify(changeLogMapper, never()).insert(org.mockito.Mockito.any(SeckillStockChangeLogEntity.class));
    }

    @Test
    void shouldThrowWhenTargetCannotBeUpdatedAfterSourceDeducted() {
        SeckillStockBucketEntity target = bucket(99L, 3, 0);
        SeckillStockBucketEntity source = bucket(10L, 2, 20);
        when(bucketMapper.selectAutoTransferTarget(1L, 1001L, 0)).thenReturn(target);
        when(bucketMapper.selectAutoTransferSource(1L, 1001L, 99L, 8, 1)).thenReturn(source);
        when(bucketMapper.deductTransferSource(10L, 2L, 8, 1)).thenReturn(1);
        when(bucketMapper.addTransferTarget(99L, 3L, 8)).thenReturn(0);

        assertThatThrownBy(() -> service.transferOnce(config()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Seckill bucket auto transfer target update failed");
        assertThat(meterRegistry.get("seckill.bucket.auto.transfer.failure").counter().count()).isEqualTo(1.0);
    }

    @Test
    void shouldRemoveSourceSurvivorWhenSourceBecomesEmpty() {
        properties.getBucket().getAutoTransfer().setSourceReserveQuantity(0);
        SeckillStockBucketEntity target = bucket(99L, 3, 0);
        SeckillStockBucketEntity source = bucket(10L, 2, 8);
        when(bucketMapper.selectAutoTransferTarget(1L, 1001L, 0)).thenReturn(target);
        when(bucketMapper.selectAutoTransferSource(1L, 1001L, 99L, 8, 0)).thenReturn(source);
        when(bucketMapper.deductTransferSource(10L, 2L, 8, 0)).thenReturn(1);
        when(bucketMapper.addTransferTarget(99L, 3L, 8)).thenReturn(1);
        when(bucketMapper.selectById(10L)).thenReturn(bucket(10L, 2, 0));
        when(bucketMapper.selectById(99L)).thenReturn(bucket(99L, 3, 8));
        when(bucketMapper.markEmptyIfNoSaleable(10L, 2L)).thenReturn(1);

        SeckillBucketAutoTransferService.AutoTransferResult result = service.transferOnce(config());

        assertThat(result.transferred()).isTrue();
        verify(availabilityCoordinator).signalPossiblyEmpty(1L, 1001L, 10L, 2, 2L);
        verify(configMapper, never()).removeSurvivorBucket(1L, 1001L, 2);
    }

    private SeckillBucketConfigEntity config() {
        SeckillBucketConfigEntity config = new SeckillBucketConfigEntity();
        config.setId(7L);
        config.setActivityId(1L);
        config.setSkuId(1001L);
        return config;
    }

    private SeckillStockBucketEntity bucket(Long id, int bucketNo, int saleableQuantity) {
        SeckillStockBucketEntity bucket = new SeckillStockBucketEntity();
        bucket.setId(id);
        bucket.setActivityId(1L);
        bucket.setSkuId(1001L);
        bucket.setBucketNo(bucketNo);
        bucket.setShardKey((long) bucketNo);
        bucket.setSaleableQuantity(saleableQuantity);
        return bucket;
    }
}

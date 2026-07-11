package com.mall.seckill.service.impl;

import com.mall.seckill.config.SeckillProperties;
import com.mall.seckill.mapper.SeckillBucketConfigMapper;
import com.mall.seckill.mapper.SeckillStockBucketMapper;
import com.mall.seckill.mapper.SeckillStockChangeLogMapper;
import com.mall.seckill.mapper.SeckillStockNotEnoughException;
import com.mall.seckill.pojo.entity.SeckillBucketConfigEntity;
import com.mall.seckill.pojo.entity.SeckillStockBucketEntity;
import com.mall.seckill.pojo.entity.SeckillStockChangeLogEntity;
import com.mall.seckill.pojo.entity.SeckillStockSnapshotEntity;
import com.mall.seckill.pojo.vo.StockVersion;
import com.mall.seckill.service.event.SeckillDeductCommittedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillBucketServiceTest {

    @Mock
    private SeckillBucketConfigMapper configMapper;

    @Mock
    private SeckillStockBucketMapper bucketMapper;

    @Mock
    private SeckillStockChangeLogMapper changeLogMapper;

    @Mock
    private SeckillBucketTransferService transferService;

    @Mock
    private SeckillBucketAvailabilityCoordinator availabilityCoordinator;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private SeckillProperties properties;
    private SeckillBucketService service;

    @BeforeEach
    void setUp() {
        properties = new SeckillProperties();
        properties.getBucket().setHotPathAggregateRead(true);
        service = new SeckillBucketService(
                configMapper,
                bucketMapper,
                changeLogMapper,
                (SeckillBucketTransferService) null,
                properties,
                availabilityCoordinator,
                eventPublisher);
    }

    @Test
    void shouldReadCenterBucketStockVersion() {
        StockVersion center = new StockVersion(12, 34L);
        when(bucketMapper.selectCenterStockVersion(1L, 1001L)).thenReturn(center);

        StockVersion result = service.aggregateStockVersion(1L, 1001L);

        assertThat(result).isEqualTo(center);
    }

    @Test
    void shouldReturnZeroStockVersionWhenCenterBucketMissing() {
        when(bucketMapper.selectCenterStockVersion(1L, 1001L)).thenReturn(null);

        StockVersion result = service.aggregateStockVersion(1L, 1001L);

        assertThat(result).isEqualTo(new StockVersion(0, 0L));
    }
    @Test
    void shouldSelectActiveBucketFromSurvivorList() {
        SeckillBucketConfigEntity config = config("3");
        SeckillStockBucketEntity bucket = bucket(99L, 3, 10);
        when(configMapper.selectEnabled(1L, 1001L)).thenReturn(config);
        when(bucketMapper.selectActiveBucketByShard(1L, 1001L, 3, 3L)).thenReturn(bucket);

        SeckillBucketService.SelectedBucket selected = service.selectBucket(1L, 1001L);

        assertThat(selected.bucketId()).isEqualTo(99L);
        assertThat(selected.bucketNo()).isEqualTo(3);
        assertThat(selected.bucketShardKey()).isEqualTo(3L);
        assertThat(selected.strategyVersion()).isEqualTo(7L);
    }

    @Test
    void shouldRejectWhenNoSurvivorBucketExists() {
        when(configMapper.selectEnabled(1L, 1001L)).thenReturn(config(""));

        assertThatThrownBy(() -> service.selectBucket(1L, 1001L))
                .isInstanceOf(SeckillStockNotEnoughException.class);
    }

    @Test
    void shouldDeductBucketWriteChangeLogWithoutPostDeductBucketRead() {
        SeckillBucketService.SelectedBucket selected = new SeckillBucketService.SelectedBucket(99L, 3, 3L, 7L, 1L);
        StockVersion aggregate = new StockVersion(8, 4L);
        when(bucketMapper.deductSaleableAndIncreaseVersionByShard(99L, 3L, 1)).thenReturn(1);
        when(bucketMapper.selectCenterStockVersion(1L, 1001L)).thenReturn(aggregate);

        SeckillBucketService.BucketMutationResult result = service.deductSelectedAndRecordChangeLog(selected, "r1", 1L, 1001L, 1);

        assertThat(result.stockVersion()).isEqualTo(aggregate);
        ArgumentCaptor<SeckillStockChangeLogEntity> changeCaptor = ArgumentCaptor.forClass(SeckillStockChangeLogEntity.class);
        verify(changeLogMapper).insert(changeCaptor.capture());
        assertThat(changeCaptor.getValue().getChangeType()).isEqualTo("DEDUCT");
        assertThat(changeCaptor.getValue().getQuantityDelta()).isEqualTo(-1);
        assertThat(changeCaptor.getValue().getBucketId()).isEqualTo(99L);
        assertThat(changeCaptor.getValue().getBucketNo()).isEqualTo(3);
        assertThat(changeCaptor.getValue().getBucketShardKey()).isEqualTo(3L);
        assertThat(changeCaptor.getValue().getAfterQuantity()).isEqualTo(SeckillBucketService.AFTER_QUANTITY_UNKNOWN);
        ArgumentCaptor<SeckillDeductCommittedEvent> eventCaptor = ArgumentCaptor.forClass(SeckillDeductCommittedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().requestId()).isEqualTo("r1");
        assertThat(eventCaptor.getValue().bucketShardKey()).isEqualTo(3L);
        verify(bucketMapper, never()).selectByIdAndShardKey(99L, 3L);
        verify(bucketMapper, never()).markEmptyIfNoSaleableByShard(99L, 3L);
        verify(configMapper, never()).removeSurvivorBucket(1L, 1001L, 3);
    }

    @Test
    void deductSelectedShouldRetryAttachedBucketAfterSuccessfulTransfer() {
        service = new SeckillBucketService(configMapper, bucketMapper, changeLogMapper, transferService, properties,
                availabilityCoordinator);
        SeckillBucketService.SelectedBucket selected = new SeckillBucketService.SelectedBucket(99L, 3, 3L, 7L, 1L);
        SeckillStockBucketEntity emptyBucket = bucket(99L, 3, 0);
        StockVersion aggregate = new StockVersion(7, 6L);
        when(bucketMapper.deductSaleableAndIncreaseVersionByShard(99L, 3L, 1)).thenReturn(0, 1);
        when(bucketMapper.selectByIdAndShardKey(99L, 3L)).thenReturn(emptyBucket);
        when(bucketMapper.markEmptyIfNoSaleableByShard(99L, 3L)).thenReturn(1);
        when(transferService.maxAttempts()).thenReturn(1);
        when(transferService.transfer("r1", 1L, 1001L, emptyBucket))
                .thenReturn(new SeckillBucketTransferService.TransferResult(true, 8));
        when(bucketMapper.selectCenterStockVersion(1L, 1001L)).thenReturn(aggregate);

        SeckillBucketService.BucketMutationResult result = service.deductSelectedAndRecordChangeLog(selected, "r1", 1L, 1001L, 1);

        assertThat(result.stockVersion()).isEqualTo(aggregate);
        assertThat(result.selectedBucket()).isEqualTo(selected);
        verify(bucketMapper, org.mockito.Mockito.times(2)).deductSaleableAndIncreaseVersionByShard(99L, 3L, 1);
        verify(transferService).transfer("r1", 1L, 1001L, emptyBucket);
        verify(availabilityCoordinator).signalPossiblyEmpty(1L, 1001L, 99L, 3, 3L);
        verify(configMapper, never()).removeSurvivorBucket(1L, 1001L, 3);
    }

    @Test
    void shouldSkipAggregateReadOnDeductHotPathWhenDisabled() {
        properties.getBucket().setHotPathAggregateRead(false);
        SeckillBucketService.SelectedBucket selected = new SeckillBucketService.SelectedBucket(99L, 3, 3L, 7L, 1L);
        when(bucketMapper.deductSaleableAndIncreaseVersionByShard(99L, 3L, 1)).thenReturn(1);

        SeckillBucketService.BucketMutationResult result = service.deductSelectedAndRecordChangeLog(selected, "r1", 1L, 1001L, 1);

        assertThat(result.stockVersion()).isNull();
        verify(bucketMapper, never()).selectByIdAndShardKey(99L, 3L);
        verify(bucketMapper, never()).selectCenterStockVersion(anyLong(), anyLong());
    }

    @Test
    void shouldThrottleRequestTriggeredTransferFallback() {
        properties.getBucket().setHotPathAggregateRead(false);
        properties.getBucket().getTransfer().setRequestFallbackMinIntervalMillis(1000);
        AtomicLong now = new AtomicLong(10_000);
        service = new SeckillBucketService(
                configMapper,
                bucketMapper,
                changeLogMapper,
                transferService,
                properties,
                now::get);
        SeckillBucketService.SelectedBucket selected = new SeckillBucketService.SelectedBucket(99L, 3, 3L, 7L, 1L);
        SeckillStockBucketEntity emptyBucket = bucket(99L, 3, 0);
        when(bucketMapper.deductSaleableAndIncreaseVersionByShard(99L, 3L, 1)).thenReturn(0, 0);
        when(bucketMapper.selectByIdAndShardKey(99L, 3L)).thenReturn(emptyBucket, emptyBucket);
        when(bucketMapper.markEmptyIfNoSaleableByShard(99L, 3L)).thenReturn(1, 1);
        when(transferService.maxAttempts()).thenReturn(1);
        when(transferService.transfer("r1", 1L, 1001L, emptyBucket))
                .thenReturn(new SeckillBucketTransferService.TransferResult(false, 0));

        assertThatThrownBy(() -> service.deductSelectedAndRecordChangeLog(selected, "r1", 1L, 1001L, 1))
                .isInstanceOf(SeckillStockNotEnoughException.class);
        assertThatThrownBy(() -> service.deductSelectedAndRecordChangeLog(selected, "r2", 1L, 1001L, 1))
                .isInstanceOf(SeckillStockNotEnoughException.class);

        verify(transferService).transfer("r1", 1L, 1001L, emptyBucket);
        verify(transferService, never()).transfer("r2", 1L, 1001L, emptyBucket);
    }

    @Test
    void shouldSkipRecentlyExhaustedBucketAcrossRequestsWithinTtl() {
        properties.getBucket().setHotPathAggregateRead(false);
        properties.getBucket().getAvailability().setExhaustedBucketTtlMillis(1000);
        AtomicLong now = new AtomicLong(10_000);
        service = new SeckillBucketService(
                configMapper,
                bucketMapper,
                changeLogMapper,
                (SeckillBucketTransferService) null,
                properties,
                now::get);
        SeckillBucketService.SelectedBucket selected = new SeckillBucketService.SelectedBucket(99L, 3, 3L, 7L, 1L);
        SeckillStockBucketEntity emptyBucket = bucket(99L, 3, 0);
        when(bucketMapper.deductSaleableAndIncreaseVersionByShard(99L, 3L, 1)).thenReturn(0);
        when(bucketMapper.selectByIdAndShardKey(99L, 3L)).thenReturn(emptyBucket);
        when(bucketMapper.markEmptyIfNoSaleableByShard(99L, 3L)).thenReturn(1);
        when(configMapper.selectEnabled(1L, 1001L)).thenReturn(config("3"));

        assertThatThrownBy(() -> service.deductSelectedAndRecordChangeLog(selected, "r1", 1L, 1001L, 1))
                .isInstanceOf(SeckillStockNotEnoughException.class);

        assertThatThrownBy(() -> service.selectBucket(1L, 1001L))
                .isInstanceOf(SeckillStockNotEnoughException.class);
        verify(bucketMapper, never()).selectActiveBucketByShard(1L, 1001L, 3, 3L);
    }

    @Test
    void shouldNotRemoveSurvivorWhenFailedDeductEmptyMarkLosesRace() {
        SeckillBucketService.SelectedBucket selected = new SeckillBucketService.SelectedBucket(99L, 3, 3L, 7L, 1L);
        SeckillStockBucketEntity emptyBucket = bucket(99L, 3, 0);
        when(bucketMapper.deductSaleableAndIncreaseVersionByShard(99L, 3L, 1)).thenReturn(0);
        when(bucketMapper.selectByIdAndShardKey(99L, 3L)).thenReturn(emptyBucket);
        when(bucketMapper.markEmptyIfNoSaleableByShard(99L, 3L)).thenReturn(0);

        assertThatThrownBy(() -> service.deductSelectedAndRecordChangeLog(selected, "r1", 1L, 1001L, 1))
                .isInstanceOf(SeckillStockNotEnoughException.class);

        verify(bucketMapper, org.mockito.Mockito.times(2)).markEmptyIfNoSaleableByShard(99L, 3L);
        verify(availabilityCoordinator, never()).signalPossiblyEmpty(
                org.mockito.Mockito.anyLong(),
                org.mockito.Mockito.anyLong(),
                org.mockito.Mockito.anyLong(),
                org.mockito.Mockito.anyInt(),
                org.mockito.Mockito.anyLong());
        verify(configMapper, never()).removeSurvivorBucket(1L, 1001L, 3);
    }

    @Test
    void shouldReleaseBucketWriteChangeLogAndOnlineBucket() {
        SeckillStockSnapshotEntity snapshot = new SeckillStockSnapshotEntity();
        snapshot.setRequestId("r1");
        snapshot.setActivityId(1L);
        snapshot.setSkuId(1001L);
        snapshot.setBucketId(99L);
        snapshot.setBucketNo(3);
        snapshot.setBucketShardKey(3L);
        snapshot.setQuantity(1);
        SeckillStockBucketEntity bucket = bucket(99L, 3, 1);
        StockVersion aggregate = new StockVersion(9, 5L);
        when(bucketMapper.releaseSaleableAndIncreaseVersionByShard(99L, 3L, 1)).thenReturn(1);
        when(bucketMapper.selectByIdAndShardKey(99L, 3L)).thenReturn(bucket);
        when(bucketMapper.selectCenterStockVersion(1L, 1001L)).thenReturn(aggregate);

        StockVersion result = service.release(snapshot);

        assertThat(result).isEqualTo(aggregate);
        ArgumentCaptor<SeckillStockChangeLogEntity> changeCaptor = ArgumentCaptor.forClass(SeckillStockChangeLogEntity.class);
        verify(changeLogMapper).insert(changeCaptor.capture());
        assertThat(changeCaptor.getValue().getChangeType()).isEqualTo("RELEASE");
        assertThat(changeCaptor.getValue().getQuantityDelta()).isEqualTo(1);
        assertThat(changeCaptor.getValue().getBucketShardKey()).isEqualTo(3L);
        verify(eventPublisher, never()).publishEvent(org.mockito.Mockito.any());
        verify(bucketMapper).updateStatus(99L, 3L, "ACTIVE");
        verify(availabilityCoordinator).signalPossiblyAvailable(1L, 1001L, 99L, 3, 3L);
        verify(configMapper, never()).updateSurvivorBuckets(1L, "3,4");
    }

    private SeckillBucketConfigEntity config(String survivors) {
        SeckillBucketConfigEntity config = new SeckillBucketConfigEntity();
        config.setId(1L);
        config.setActivityId(1L);
        config.setSkuId(1001L);
        config.setStrategyVersion(7L);
        config.setSurvivorBuckets(survivors);
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

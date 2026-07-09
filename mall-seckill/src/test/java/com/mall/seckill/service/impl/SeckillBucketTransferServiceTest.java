package com.mall.seckill.service.impl;

import com.mall.common.exception.BusinessException;
import com.mall.seckill.config.SeckillProperties;
import com.mall.seckill.mapper.SeckillBucketConfigMapper;
import com.mall.seckill.mapper.SeckillStockBucketMapper;
import com.mall.seckill.mapper.SeckillStockChangeLogMapper;
import com.mall.seckill.pojo.entity.SeckillStockBucketEntity;
import com.mall.seckill.pojo.entity.SeckillStockChangeLogEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillBucketTransferServiceTest {

    @Mock
    private SeckillStockBucketMapper bucketMapper;

    @Mock
    private SeckillBucketConfigMapper configMapper;

    @Mock
    private SeckillStockChangeLogMapper changeLogMapper;

    @Mock
    private ObjectProvider<RedissonClient> redissonProvider;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock lock;

    @Mock
    private SeckillBucketAvailabilityCoordinator availabilityCoordinator;

    private SeckillProperties properties;
    private SeckillBucketTransferService service;

    @BeforeEach
    void setUp() {
        properties = new SeckillProperties();
        properties.getBucket().getTransfer().setEnabled(true);
        properties.getBucket().getTransfer().setSize(8);
        when(redissonProvider.getIfAvailable()).thenReturn(redissonClient);
        service = new SeckillBucketTransferService(
                bucketMapper,
                configMapper,
                changeLogMapper,
                redissonProvider,
                properties,
                availabilityCoordinator);
}
    @Test
    void shouldTransferFromSourceToTargetAndWritePairedChangeLogs() throws InterruptedException {
        SeckillStockBucketEntity target = bucket(99L, 3, 0);
        SeckillStockBucketEntity source = bucket(10L, 2, 10);
        when(redissonClient.getLock("seckill:bucket:transfer:1:1001:99")).thenReturn(lock);
        when(lock.tryLock(20L, 200L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(bucketMapper.selectTransferSource(1L, 1001L, 99L, 1)).thenReturn(source);
        when(bucketMapper.deductTransferSource(10L, 2L, 8, 1)).thenReturn(1);
        when(bucketMapper.addTransferTarget(99L, 3L, 8)).thenReturn(1);
        when(bucketMapper.selectById(10L)).thenReturn(bucket(10L, 2, 2));
        when(bucketMapper.selectById(99L)).thenReturn(bucket(99L, 3, 8));

        SeckillBucketTransferService.TransferResult result = service.transfer("r1", 1L, 1001L, target);

        assertThat(result.transferred()).isTrue();
        assertThat(result.quantity()).isEqualTo(8);
        verify(availabilityCoordinator).signalPossiblyAvailable(1L, 1001L, 99L, 3, 3L);
        verify(configMapper, never()).addSurvivorBucket(1L, 1001L, 3);
        verify(lock).unlock();
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
        assertThat(changes.stream().mapToInt(SeckillStockChangeLogEntity::getQuantityDelta).sum()).isZero();
    }

    @Test
    void shouldKeepOneQuantityInSourceBucket() throws InterruptedException {
        SeckillStockBucketEntity target = bucket(99L, 3, 0);
        SeckillStockBucketEntity source = bucket(10L, 2, 5);
        when(redissonClient.getLock("seckill:bucket:transfer:1:1001:99")).thenReturn(lock);
        when(lock.tryLock(20L, 200L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(bucketMapper.selectTransferSource(1L, 1001L, 99L, 1)).thenReturn(source);
        when(bucketMapper.deductTransferSource(10L, 2L, 4, 1)).thenReturn(1);
        when(bucketMapper.addTransferTarget(99L, 3L, 4)).thenReturn(1);
        when(bucketMapper.selectById(10L)).thenReturn(bucket(10L, 2, 1));
        when(bucketMapper.selectById(99L)).thenReturn(bucket(99L, 3, 4));

        SeckillBucketTransferService.TransferResult result = service.transfer("r1", 1L, 1001L, target);

        assertThat(result.transferred()).isTrue();
        assertThat(result.quantity()).isEqualTo(4);
        verify(bucketMapper).deductTransferSource(10L, 2L, 4, 1);
    }

    @Test
    void shouldRemoveTransferSourceSurvivorOnlyWhenConditionalEmptyMarkSucceeds() throws InterruptedException {
        SeckillStockBucketEntity target = bucket(99L, 3, 0);
        SeckillStockBucketEntity source = bucket(10L, 2, 9);
        when(redissonClient.getLock("seckill:bucket:transfer:1:1001:99")).thenReturn(lock);
        when(lock.tryLock(20L, 200L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(bucketMapper.selectTransferSource(1L, 1001L, 99L, 1)).thenReturn(source);
        when(bucketMapper.deductTransferSource(10L, 2L, 8, 1)).thenReturn(1);
        when(bucketMapper.addTransferTarget(99L, 3L, 8)).thenReturn(1);
        when(bucketMapper.selectById(10L)).thenReturn(bucket(10L, 2, 0));
        when(bucketMapper.selectById(99L)).thenReturn(bucket(99L, 3, 8));
        when(bucketMapper.markEmptyIfNoSaleable(10L, 2L)).thenReturn(1);

        SeckillBucketTransferService.TransferResult result = service.transfer("r1", 1L, 1001L, target);

        assertThat(result.transferred()).isTrue();
        verify(bucketMapper).markEmptyIfNoSaleable(10L, 2L);
        verify(availabilityCoordinator).signalPossiblyEmpty(1L, 1001L, 10L, 2, 2L);
        verify(configMapper, never()).removeSurvivorBucket(1L, 1001L, 2);
    }

    @Test
    void shouldKeepTransferSourceSurvivorWhenConditionalEmptyMarkLosesRace() throws InterruptedException {
        SeckillStockBucketEntity target = bucket(99L, 3, 0);
        SeckillStockBucketEntity source = bucket(10L, 2, 9);
        when(redissonClient.getLock("seckill:bucket:transfer:1:1001:99")).thenReturn(lock);
        when(lock.tryLock(20L, 200L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(bucketMapper.selectTransferSource(1L, 1001L, 99L, 1)).thenReturn(source);
        when(bucketMapper.deductTransferSource(10L, 2L, 8, 1)).thenReturn(1);
        when(bucketMapper.addTransferTarget(99L, 3L, 8)).thenReturn(1);
        when(bucketMapper.selectById(10L)).thenReturn(bucket(10L, 2, 0));
        when(bucketMapper.selectById(99L)).thenReturn(bucket(99L, 3, 8));
        when(bucketMapper.markEmptyIfNoSaleable(10L, 2L)).thenReturn(0);

        SeckillBucketTransferService.TransferResult result = service.transfer("r1", 1L, 1001L, target);

        assertThat(result.transferred()).isTrue();
        verify(bucketMapper).markEmptyIfNoSaleable(10L, 2L);
        verify(availabilityCoordinator, never()).signalPossiblyEmpty(
                org.mockito.Mockito.anyLong(),
                org.mockito.Mockito.anyLong(),
                org.mockito.Mockito.anyLong(),
                org.mockito.Mockito.anyInt(),
                org.mockito.Mockito.anyLong());
        verify(configMapper, never()).removeSurvivorBucket(1L, 1001L, 2);
    }

    @Test
    void shouldSkipWhenTransferDisabled() {
        properties.getBucket().getTransfer().setEnabled(false);

        SeckillBucketTransferService.TransferResult result = service.transfer("r1", 1L, 1001L, bucket(99L, 3, 0));

        assertThat(result.transferred()).isFalse();
        verify(redissonClient, never()).getLock("seckill:bucket:transfer:1:1001:99");
    }

    @Test
    void shouldSkipWhenLockNotAcquired() throws InterruptedException {
        when(redissonClient.getLock("seckill:bucket:transfer:1:1001:99")).thenReturn(lock);
        when(lock.tryLock(20L, 200L, TimeUnit.MILLISECONDS)).thenReturn(false);

        SeckillBucketTransferService.TransferResult result = service.transfer("r1", 1L, 1001L, bucket(99L, 3, 0));

        assertThat(result.transferred()).isFalse();
        verify(bucketMapper, never()).selectTransferSource(1L, 1001L, 99L, 1);
    }

    @Test
    void shouldSkipWhenNoSourceBucketExists() throws InterruptedException {
        when(redissonClient.getLock("seckill:bucket:transfer:1:1001:99")).thenReturn(lock);
        when(lock.tryLock(20L, 200L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(bucketMapper.selectTransferSource(1L, 1001L, 99L, 1)).thenReturn(null);

        SeckillBucketTransferService.TransferResult result = service.transfer("r1", 1L, 1001L, bucket(99L, 3, 0));

        assertThat(result.transferred()).isFalse();
        verify(bucketMapper, never()).addTransferTarget(99L, 3L, 8);
        verify(changeLogMapper, never()).insert(org.mockito.Mockito.any(SeckillStockChangeLogEntity.class));
    }

    @Test
    void shouldSkipWhenSourceDeductLosesRace() throws InterruptedException {
        SeckillStockBucketEntity source = bucket(10L, 2, 10);
        when(redissonClient.getLock("seckill:bucket:transfer:1:1001:99")).thenReturn(lock);
        when(lock.tryLock(20L, 200L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(bucketMapper.selectTransferSource(1L, 1001L, 99L, 1)).thenReturn(source);
        when(bucketMapper.deductTransferSource(10L, 2L, 8, 1)).thenReturn(0);

        SeckillBucketTransferService.TransferResult result = service.transfer("r1", 1L, 1001L, bucket(99L, 3, 0));

        assertThat(result.transferred()).isFalse();
        verify(bucketMapper, never()).addTransferTarget(99L, 3L, 8);
        verify(changeLogMapper, never()).insert(org.mockito.Mockito.any(SeckillStockChangeLogEntity.class));
    }

    @Test
    void shouldThrowWhenTargetCannotBeUpdatedAfterSourceDeducted() throws InterruptedException {
        SeckillStockBucketEntity source = bucket(10L, 2, 10);
        when(redissonClient.getLock("seckill:bucket:transfer:1:1001:99")).thenReturn(lock);
        when(lock.tryLock(20L, 200L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(bucketMapper.selectTransferSource(1L, 1001L, 99L, 1)).thenReturn(source);
        when(bucketMapper.deductTransferSource(10L, 2L, 8, 1)).thenReturn(1);
        when(bucketMapper.addTransferTarget(99L, 3L, 8)).thenReturn(0);

        assertThatThrownBy(() -> service.transfer("r1", 1L, 1001L, bucket(99L, 3, 0)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Seckill bucket transfer target update failed");
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

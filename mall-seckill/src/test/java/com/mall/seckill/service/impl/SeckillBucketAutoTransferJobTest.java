package com.mall.seckill.service.impl;

import com.mall.seckill.config.SeckillProperties;
import com.mall.seckill.mapper.SeckillBucketConfigMapper;
import com.mall.seckill.pojo.entity.SeckillBucketConfigEntity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillBucketAutoTransferJobTest {

    @Mock
    private SeckillBucketConfigMapper configMapper;

    @Mock
    private SeckillBucketReconcileService reconcileService;

    @Mock
    private SeckillBucketAutoTransferService autoTransferService;

    @Mock
    private ObjectProvider<RedissonClient> redissonProvider;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock lock;

    private SeckillProperties properties;
    private SeckillBucketAutoTransferJob job;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        properties = new SeckillProperties();
        properties.getBucket().getAutoTransfer().setEnabled(true);
        properties.getBucket().getAutoTransfer().setBatchSize(3);
        properties.getBucket().getAutoTransfer().setMaxPairsPerSku(2);
        meterRegistry = new SimpleMeterRegistry();
        when(redissonProvider.getIfAvailable()).thenReturn(redissonClient);
        job = new SeckillBucketAutoTransferJob(
                configMapper,
                reconcileService,
                autoTransferService,
                redissonProvider,
                properties,
                meterRegistry);
    }

    @Test
    void shouldLockReconcileAndRunNoMoreThanMaxPairs() throws InterruptedException {
        SeckillBucketConfigEntity config = config();
        when(configMapper.selectEnabledForMaintenance(3)).thenReturn(List.of(config));
        when(redissonClient.getLock("seckill:bucket:auto-transfer:1:1001")).thenReturn(lock);
        when(lock.tryLock(20L, 500L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(autoTransferService.transferOnce(config)).thenReturn(
                SeckillBucketAutoTransferService.AutoTransferResult.transferred(8),
                SeckillBucketAutoTransferService.AutoTransferResult.transferred(8));

        job.transfer();

        verify(reconcileService).reconcile(config);
        verify(autoTransferService, org.mockito.Mockito.times(2)).transferOnce(config);
        assertThat(meterRegistry.get("seckill.bucket.auto.transfer.config.count").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("seckill.bucket.auto.transfer.total").timer().count()).isEqualTo(1L);
        verify(lock).unlock();
    }

    @Test
    void shouldSkipConfigWhenLockIsNotAcquired() throws InterruptedException {
        SeckillBucketConfigEntity config = config();
        when(configMapper.selectEnabledForMaintenance(3)).thenReturn(List.of(config));
        when(redissonClient.getLock("seckill:bucket:auto-transfer:1:1001")).thenReturn(lock);
        when(lock.tryLock(20L, 500L, TimeUnit.MILLISECONDS)).thenReturn(false);

        job.transfer();

        verify(reconcileService, never()).reconcile(config);
        verify(autoTransferService, never()).transferOnce(config);
        assertThat(meterRegistry.get("seckill.bucket.auto.transfer.lock.miss").counter().count()).isEqualTo(1.0);
    }

    @Test
    void shouldStopCurrentSkuWhenTargetIsMissing() throws InterruptedException {
        SeckillBucketConfigEntity config = config();
        when(configMapper.selectEnabledForMaintenance(3)).thenReturn(List.of(config));
        when(redissonClient.getLock("seckill:bucket:auto-transfer:1:1001")).thenReturn(lock);
        when(lock.tryLock(20L, 500L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(autoTransferService.transferOnce(config)).thenReturn(
                SeckillBucketAutoTransferService.AutoTransferResult.targetMiss());

        job.transfer();

        verify(autoTransferService).transferOnce(config);
        verify(lock).unlock();
    }

    private SeckillBucketConfigEntity config() {
        SeckillBucketConfigEntity config = new SeckillBucketConfigEntity();
        config.setId(7L);
        config.setActivityId(1L);
        config.setSkuId(1001L);
        return config;
    }
}

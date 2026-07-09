package com.mall.seckill.service.impl;

import com.mall.seckill.mapper.SeckillBucketConfigMapper;
import com.mall.seckill.mapper.SeckillStockBucketMapper;
import com.mall.seckill.pojo.entity.SeckillBucketConfigEntity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillBucketReconcileServiceTest {

    @Mock
    private SeckillStockBucketMapper bucketMapper;

    @Mock
    private SeckillBucketConfigMapper configMapper;

    private SeckillBucketReconcileService service;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new SeckillBucketReconcileService(bucketMapper, configMapper, meterRegistry);
    }

    @Test
    void shouldFixBucketStatusAndRebuildSurvivors() {
        SeckillBucketConfigEntity config = config("2,3");
        when(bucketMapper.activatePositiveBuckets(1L, 1001L)).thenReturn(1);
        when(bucketMapper.markActiveNonPositiveBucketsEmpty(1L, 1001L)).thenReturn(2);
        when(bucketMapper.selectActivePositiveBucketNos(1L, 1001L)).thenReturn(List.of(3, 1, 3));
        when(configMapper.updateSurvivorBuckets(7L, "1,3")).thenReturn(1);

        SeckillBucketReconcileService.ReconcileResult result = service.reconcile(config);

        assertThat(result.activated()).isEqualTo(1);
        assertThat(result.emptied()).isEqualTo(2);
        assertThat(result.survivorUpdated()).isEqualTo(1);
        assertThat(meterRegistry.get("seckill.bucket.reconcile.status.fixed").counter().count()).isEqualTo(3.0);
        assertThat(meterRegistry.get("seckill.bucket.reconcile.survivor.updated").counter().count()).isEqualTo(1.0);
        verify(configMapper).updateSurvivorBuckets(7L, "1,3");
    }

    @Test
    void shouldSkipSurvivorUpdateWhenAlreadyCurrent() {
        SeckillBucketConfigEntity config = config("1,3");
        when(bucketMapper.selectActivePositiveBucketNos(1L, 1001L)).thenReturn(List.of(1, 3));

        SeckillBucketReconcileService.ReconcileResult result = service.reconcile(config);

        assertThat(result.survivorUpdated()).isZero();
        verify(configMapper, never()).updateSurvivorBuckets(7L, "1,3");
    }

    @Test
    void shouldReturnEmptyResultWhenConfigIsInvalid() {
        SeckillBucketReconcileService.ReconcileResult result = service.reconcile(new SeckillBucketConfigEntity());

        assertThat(result.activated()).isZero();
        assertThat(result.emptied()).isZero();
        assertThat(result.survivorUpdated()).isZero();
        assertThat(meterRegistry.get("seckill.bucket.reconcile.config.invalid").counter().count()).isEqualTo(1.0);
        verify(bucketMapper, never()).activatePositiveBuckets(1L, 1001L);
    }

    private SeckillBucketConfigEntity config(String survivors) {
        SeckillBucketConfigEntity config = new SeckillBucketConfigEntity();
        config.setId(7L);
        config.setActivityId(1L);
        config.setSkuId(1001L);
        config.setSurvivorBuckets(survivors);
        return config;
    }
}

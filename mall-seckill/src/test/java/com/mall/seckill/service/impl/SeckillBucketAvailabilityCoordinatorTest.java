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
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillBucketAvailabilityCoordinatorTest {

    @Mock
    private SeckillBucketConfigMapper configMapper;

    @Mock
    private SeckillBucketReconcileService reconcileService;

    @Mock
    private ObjectProvider<org.redisson.api.RedissonClient> redissonProvider;

    private SeckillProperties properties;
    private AtomicLong now;
    private SeckillBucketAvailabilityCoordinator coordinator;

    @BeforeEach
    void setUp() {
        properties = new SeckillProperties();
        properties.getBucket().getAvailability().setEnabled(true);
        properties.getBucket().getAvailability().setFlushDelayMillis(200);
        now = new AtomicLong(10_000);
        when(redissonProvider.getIfAvailable()).thenReturn(null);
        coordinator = new SeckillBucketAvailabilityCoordinator(
                configMapper,
                reconcileService,
                redissonProvider,
                properties,
                new SimpleMeterRegistry(),
                now::get);
    }

    @Test
    void shouldCoalesceSignalsByActivityAndSkuUntilFlushDelayPasses() {
        SeckillBucketConfigEntity config = config();
        coordinator.signalPossiblyEmpty(1L, 1001L, 99L, 3, 3L);
        coordinator.signalPossiblyAvailable(1L, 1001L, 100L, 4, 4L);

        coordinator.flushDueSignals();

        assertThat(coordinator.pendingSignalCount()).isEqualTo(1);
        verify(configMapper, never()).selectEnabled(1L, 1001L);

        now.addAndGet(250);
        when(configMapper.selectEnabled(1L, 1001L)).thenReturn(config);

        coordinator.flushDueSignals();

        assertThat(coordinator.pendingSignalCount()).isZero();
        verify(reconcileService).reconcile(config);
    }

    @Test
    void shouldDropSignalWhenConfigNoLongerExists() {
        coordinator.signalPossiblyEmpty(1L, 1001L, 99L, 3, 3L);
        now.addAndGet(250);
        when(configMapper.selectEnabled(1L, 1001L)).thenReturn(null);

        coordinator.flushDueSignals();

        assertThat(coordinator.pendingSignalCount()).isZero();
        verify(reconcileService, never()).reconcile(org.mockito.Mockito.any());
    }

    @Test
    void shouldIgnoreSignalsWhenAvailabilityDisabled() {
        properties.getBucket().getAvailability().setEnabled(false);

        coordinator.signalPossiblyEmpty(1L, 1001L, 99L, 3, 3L);

        assertThat(coordinator.pendingSignalCount()).isZero();
    }

    private SeckillBucketConfigEntity config() {
        SeckillBucketConfigEntity config = new SeckillBucketConfigEntity();
        config.setId(7L);
        config.setActivityId(1L);
        config.setSkuId(1001L);
        config.setSurvivorBuckets("3,4");
        return config;
    }
}

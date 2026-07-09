package com.mall.seckill.service.impl;

import com.mall.seckill.config.SeckillProperties;
import com.mall.seckill.mapper.SeckillBucketConfigMapper;
import com.mall.seckill.pojo.entity.SeckillBucketConfigEntity;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

@Component
public class SeckillBucketAvailabilityCoordinator {

    private static final int MAX_BATCH_SIZE = 1000;

    private final SeckillBucketConfigMapper configMapper;
    private final SeckillBucketReconcileService reconcileService;
    private final RedissonClient redissonClient;
    private final SeckillProperties properties;
    private final LongSupplier currentTimeMillis;
    private final ConcurrentHashMap<ConfigKey, PendingSignal> pendingSignals = new ConcurrentHashMap<>();
    private final Counter signalCounter;
    private final Counter reconciledCounter;
    private final Counter skippedCounter;
    private final Counter lockMissCounter;
    private final Counter failureCounter;

    @Autowired
    public SeckillBucketAvailabilityCoordinator(SeckillBucketConfigMapper configMapper,
                                                SeckillBucketReconcileService reconcileService,
                                                ObjectProvider<RedissonClient> redissonClient,
                                                SeckillProperties properties,
                                                MeterRegistry meterRegistry) {
        this(configMapper,
                reconcileService,
                redissonClient,
                properties,
                meterRegistry,
                System::currentTimeMillis);
    }

    SeckillBucketAvailabilityCoordinator(SeckillBucketConfigMapper configMapper,
                                         SeckillBucketReconcileService reconcileService,
                                         ObjectProvider<RedissonClient> redissonClient,
                                         SeckillProperties properties,
                                         MeterRegistry meterRegistry,
                                         LongSupplier currentTimeMillis) {
        this.configMapper = configMapper;
        this.reconcileService = reconcileService;
        this.redissonClient = redissonClient == null ? null : redissonClient.getIfAvailable();
        this.properties = properties == null ? new SeckillProperties() : properties;
        this.currentTimeMillis = currentTimeMillis == null ? System::currentTimeMillis : currentTimeMillis;
        this.signalCounter = counter(meterRegistry, "seckill.bucket.availability.signal",
                "Seckill bucket availability signal count");
        this.reconciledCounter = counter(meterRegistry, "seckill.bucket.availability.reconciled",
                "Seckill bucket availability reconcile count");
        this.skippedCounter = counter(meterRegistry, "seckill.bucket.availability.skipped",
                "Seckill bucket availability skipped signal count");
        this.lockMissCounter = counter(meterRegistry, "seckill.bucket.availability.lock.miss",
                "Seckill bucket availability lock miss count");
        this.failureCounter = counter(meterRegistry, "seckill.bucket.availability.failure",
                "Seckill bucket availability failure count");
    }

    public void signalPossiblyEmpty(Long activityId, Long skuId, Long bucketId, Integer bucketNo, Long bucketShardKey) {
        signal(activityId, skuId);
    }

    public void signalPossiblyAvailable(Long activityId, Long skuId, Long bucketId, Integer bucketNo, Long bucketShardKey) {
        signal(activityId, skuId);
    }

    @Scheduled(fixedDelayString = "${mall.seckill.bucket.availability.fixed-delay:100}")
    public void flushDueSignals() {
        if (!enabled()) {
            pendingSignals.clear();
            return;
        }
        long now = currentTimeMillis.getAsLong();
        List<ConfigKey> dueKeys = new ArrayList<>();
        for (var entry : pendingSignals.entrySet()) {
            if (entry.getValue().dueMillis() <= now && dueKeys.size() < batchSize()) {
                dueKeys.add(entry.getKey());
            }
        }
        for (ConfigKey key : dueKeys) {
            flushOne(key);
        }
    }

    public int pendingSignalCount() {
        return pendingSignals.size();
    }

    private void signal(Long activityId, Long skuId) {
        if (!enabled() || activityId == null || skuId == null) {
            skippedCounter.increment();
            return;
        }
        long now = currentTimeMillis.getAsLong();
        ConfigKey key = new ConfigKey(activityId, skuId);
        pendingSignals.compute(key, (ignored, existing) -> {
            if (existing == null) {
                return new PendingSignal(now, now + flushDelayMillis());
            }
            return new PendingSignal(existing.firstSignalMillis(), now + flushDelayMillis());
        });
        signalCounter.increment();
    }

    private void flushOne(ConfigKey key) {
        RLock lock = null;
        boolean locked = false;
        try {
            lock = availabilityLock(key);
            if (lock != null) {
                locked = tryLock(lock);
                if (!locked) {
                    lockMissCounter.increment();
                    return;
                }
            }
            PendingSignal removed = pendingSignals.remove(key);
            if (removed == null) {
                return;
            }
            SeckillBucketConfigEntity config = configMapper.selectEnabled(key.activityId(), key.skuId());
            if (config == null) {
                skippedCounter.increment();
                return;
            }
            reconcileService.reconcile(config);
            reconciledCounter.increment();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failureCounter.increment();
        } catch (RuntimeException exception) {
            failureCounter.increment();
            throw exception;
        } finally {
            unlockIfHeld(lock, locked);
        }
    }

    private RLock availabilityLock(ConfigKey key) {
        if (redissonClient == null) {
            return null;
        }
        return redissonClient.getLock("seckill:bucket:availability:" + key.activityId() + ":" + key.skuId());
    }

    private boolean tryLock(RLock lock) throws InterruptedException {
        long waitMillis = Math.max(0, availability().getLockWaitMillis());
        long leaseMillis = availability().getLockLeaseMillis();
        if (leaseMillis > 0) {
            return lock.tryLock(waitMillis, leaseMillis, TimeUnit.MILLISECONDS);
        }
        return lock.tryLock(waitMillis, TimeUnit.MILLISECONDS);
    }

    private void unlockIfHeld(RLock lock, boolean locked) {
        if (!locked || lock == null) {
            return;
        }
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (IllegalMonitorStateException ignored) {
            // A late lock expiry should not make async survivor maintenance fail the scheduler.
        }
    }

    private boolean enabled() {
        return availability().isEnabled();
    }

    private long flushDelayMillis() {
        return Math.max(0, availability().getFlushDelayMillis());
    }

    private int batchSize() {
        return Math.max(1, Math.min(availability().getBatchSize(), MAX_BATCH_SIZE));
    }

    private SeckillProperties.Availability availability() {
        return properties.getBucket().getAvailability();
    }

    private Counter counter(MeterRegistry meterRegistry, String name, String description) {
        return Counter.builder(name)
                .description(description)
                .register(meterRegistry);
    }

    private record ConfigKey(Long activityId, Long skuId) {

        private ConfigKey {
            Objects.requireNonNull(activityId, "activityId must not be null");
            Objects.requireNonNull(skuId, "skuId must not be null");
        }
    }

    private record PendingSignal(long firstSignalMillis, long dueMillis) {
    }
}

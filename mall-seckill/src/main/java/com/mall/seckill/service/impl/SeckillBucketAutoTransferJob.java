package com.mall.seckill.service.impl;

import com.mall.seckill.config.SeckillProperties;
import com.mall.seckill.mapper.SeckillBucketConfigMapper;
import com.mall.seckill.pojo.entity.SeckillBucketConfigEntity;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix = "mall.seckill.bucket.auto-transfer", name = "enabled", havingValue = "true")
public class SeckillBucketAutoTransferJob {

    private static final Logger log = LoggerFactory.getLogger(SeckillBucketAutoTransferJob.class);
    private static final int MAX_BATCH_SIZE = 1000;
    private static final int MAX_PAIRS_PER_SKU = 100;

    private final SeckillBucketConfigMapper configMapper;
    private final SeckillBucketReconcileService reconcileService;
    private final SeckillBucketAutoTransferService autoTransferService;
    private final RedissonClient redissonClient;
    private final SeckillProperties properties;
    private final MeterRegistry meterRegistry;
    private final Timer totalTimer;
    private final Counter configCounter;
    private final Counter lockMissCounter;
    private final Counter failureCounter;

    public SeckillBucketAutoTransferJob(SeckillBucketConfigMapper configMapper,
                                        SeckillBucketReconcileService reconcileService,
                                        SeckillBucketAutoTransferService autoTransferService,
                                        ObjectProvider<RedissonClient> redissonClient,
                                        SeckillProperties properties,
                                        MeterRegistry meterRegistry) {
        this.configMapper = configMapper;
        this.reconcileService = reconcileService;
        this.autoTransferService = autoTransferService;
        this.redissonClient = redissonClient.getIfAvailable();
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.totalTimer = timer(
                "seckill.bucket.auto.transfer.total",
                "Seckill bucket auto transfer scheduled round latency");
        this.configCounter = counter(
                "seckill.bucket.auto.transfer.config.count",
                "Seckill bucket auto transfer scanned config count");
        this.lockMissCounter = counter(
                "seckill.bucket.auto.transfer.lock.miss",
                "Seckill bucket auto transfer lock miss count");
        this.failureCounter = counter(
                "seckill.bucket.auto.transfer.job.failure",
                "Seckill bucket auto transfer job failure count");
    }

    @Scheduled(fixedDelayString = "${mall.seckill.bucket.auto-transfer.fixed-delay:60000}")
    public void transfer() {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            if (redissonClient == null) {
                failureCounter.increment();
                log.warn("Skip seckill bucket auto transfer because RedissonClient is unavailable");
                return;
            }
            List<SeckillBucketConfigEntity> configs = configMapper.selectEnabledForMaintenance(batchSize());
            if (!configs.isEmpty()) {
                configCounter.increment(configs.size());
            }
            for (SeckillBucketConfigEntity config : configs) {
                transfer(config);
            }
        } finally {
            sample.stop(totalTimer);
        }
    }

    private void transfer(SeckillBucketConfigEntity config) {
        if (config == null || config.getActivityId() == null || config.getSkuId() == null) {
            return;
        }
        RLock lock = redissonClient.getLock(lockKey(config.getActivityId(), config.getSkuId()));
        boolean locked;
        try {
            locked = tryLock(lock);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failureCounter.increment();
            return;
        }
        if (!locked) {
            lockMissCounter.increment();
            return;
        }
        try {
            reconcileService.reconcile(config);
            transferLocked(config);
        } catch (RuntimeException exception) {
            failureCounter.increment();
            log.warn("Failed to auto transfer seckill bucket activityId={} skuId={}",
                    config.getActivityId(),
                    config.getSkuId(),
                    exception);
        } finally {
            unlockIfHeldByCurrentThread(lock);
        }
    }

    private void transferLocked(SeckillBucketConfigEntity config) {
        int maxPairs = maxPairsPerSku();
        for (int i = 0; i < maxPairs; i++) {
            SeckillBucketAutoTransferService.AutoTransferResult result = autoTransferService.transferOnce(config);
            if (result.transferred()
                    || result.outcome() == SeckillBucketAutoTransferService.AutoTransferOutcome.SOURCE_DEDUCT_LOST) {
                continue;
            }
            return;
        }
    }

    private int batchSize() {
        int configured = properties.getBucket().getAutoTransfer().getBatchSize();
        return Math.max(1, Math.min(configured, MAX_BATCH_SIZE));
    }

    private int maxPairsPerSku() {
        int configured = properties.getBucket().getAutoTransfer().getMaxPairsPerSku();
        return Math.max(0, Math.min(configured, MAX_PAIRS_PER_SKU));
    }

    private boolean tryLock(RLock lock) throws InterruptedException {
        SeckillProperties.AutoTransfer autoTransfer = properties.getBucket().getAutoTransfer();
        long waitMillis = Math.max(0, autoTransfer.getLockWaitMillis());
        long leaseMillis = autoTransfer.getLockLeaseMillis();
        if (leaseMillis > 0) {
            return lock.tryLock(waitMillis, leaseMillis, TimeUnit.MILLISECONDS);
        }
        return lock.tryLock(waitMillis, TimeUnit.MILLISECONDS);
    }

    private void unlockIfHeldByCurrentThread(RLock lock) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (IllegalMonitorStateException ignored) {
            // A late lock expiry should not fail the maintenance round.
        }
    }

    private String lockKey(Long activityId, Long skuId) {
        return "seckill:bucket:auto-transfer:" + activityId + ":" + skuId;
    }

    private Timer timer(String name, String description) {
        return Timer.builder(name)
                .description(description)
                .register(meterRegistry);
    }

    private Counter counter(String name, String description) {
        return Counter.builder(name)
                .description(description)
                .register(meterRegistry);
    }
}

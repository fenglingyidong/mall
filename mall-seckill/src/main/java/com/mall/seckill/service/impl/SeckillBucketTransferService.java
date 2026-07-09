package com.mall.seckill.service.impl;

import com.mall.common.exception.BusinessException;
import com.mall.seckill.config.SeckillProperties;
import com.mall.seckill.mapper.SeckillBucketConfigMapper;
import com.mall.seckill.mapper.SeckillStockBucketMapper;
import com.mall.seckill.mapper.SeckillStockChangeLogMapper;
import com.mall.seckill.pojo.entity.SeckillStockBucketEntity;
import com.mall.seckill.pojo.entity.SeckillStockChangeLogEntity;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Component
public class SeckillBucketTransferService {

    static final String CHANGE_TRANSFER_OUT = "TRANSFER_OUT";
    static final String CHANGE_TRANSFER_IN = "TRANSFER_IN";
    static final String CHANGE_STATUS_NEW = "NEW";
    private static final int SOURCE_RESERVE_QUANTITY = 1;

    private final SeckillStockBucketMapper bucketMapper;
    private final SeckillBucketConfigMapper configMapper;
    private final SeckillStockChangeLogMapper changeLogMapper;
    private final RedissonClient redissonClient;
    private final SeckillProperties properties;
    private final MeterRegistry meterRegistry;
    private final SeckillBucketAvailabilityCoordinator availabilityCoordinator;
    private final Timer totalTimer;
    private final Timer lockWaitTimer;
    private final Timer dbMoveTimer;
    private final Timer changeLogInsertTimer;

    public SeckillBucketTransferService(SeckillStockBucketMapper bucketMapper,
                                        SeckillBucketConfigMapper configMapper,
                                        SeckillStockChangeLogMapper changeLogMapper,
                                        ObjectProvider<RedissonClient> redissonClient,
                                        SeckillProperties properties) {
        this(bucketMapper, configMapper, changeLogMapper, redissonClient, properties, new SimpleMeterRegistry(),
                (SeckillBucketAvailabilityCoordinator) null);
    }

    SeckillBucketTransferService(SeckillStockBucketMapper bucketMapper,
                                 SeckillBucketConfigMapper configMapper,
                                 SeckillStockChangeLogMapper changeLogMapper,
                                 ObjectProvider<RedissonClient> redissonClient,
                                 SeckillProperties properties,
                                 SeckillBucketAvailabilityCoordinator availabilityCoordinator) {
        this(bucketMapper, configMapper, changeLogMapper, redissonClient, properties, new SimpleMeterRegistry(),
                availabilityCoordinator);
    }

    @Autowired
    public SeckillBucketTransferService(SeckillStockBucketMapper bucketMapper,
                                        SeckillBucketConfigMapper configMapper,
                                        SeckillStockChangeLogMapper changeLogMapper,
                                        ObjectProvider<RedissonClient> redissonClient,
                                        SeckillProperties properties,
                                        MeterRegistry meterRegistry,
                                        ObjectProvider<SeckillBucketAvailabilityCoordinator> availabilityCoordinator) {
        this(bucketMapper, configMapper, changeLogMapper, redissonClient, properties, meterRegistry,
                availabilityCoordinator == null ? null : availabilityCoordinator.getIfAvailable());
    }

    private SeckillBucketTransferService(SeckillStockBucketMapper bucketMapper,
                                         SeckillBucketConfigMapper configMapper,
                                         SeckillStockChangeLogMapper changeLogMapper,
                                         ObjectProvider<RedissonClient> redissonClient,
                                         SeckillProperties properties,
                                         MeterRegistry meterRegistry,
                                         SeckillBucketAvailabilityCoordinator availabilityCoordinator) {
        this.bucketMapper = bucketMapper;
        this.configMapper = configMapper;
        this.changeLogMapper = changeLogMapper;
        this.redissonClient = redissonClient.getIfAvailable();
        this.properties = properties;
        this.meterRegistry = meterRegistry == null ? new SimpleMeterRegistry() : meterRegistry;
        this.availabilityCoordinator = availabilityCoordinator;
        this.totalTimer = timer("seckill.submit.record.bucket.transfer.total", "Official submit request-triggered bucket transfer total latency");
        this.lockWaitTimer = timer("seckill.submit.record.bucket.transfer.lock.wait", "Official submit request-triggered bucket transfer lock wait latency");
        this.dbMoveTimer = timer("seckill.submit.record.bucket.transfer.db.move", "Official submit request-triggered bucket transfer source and target update latency");
        this.changeLogInsertTimer = timer("seckill.submit.record.bucket.transfer.change-log.insert", "Official submit request-triggered bucket transfer change log insert latency");
    }

    @Transactional(rollbackFor = Exception.class)
    public TransferResult transfer(String requestId,
                                   Long activityId,
                                   Long skuId,
                                   SeckillStockBucketEntity targetBucket) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            if (!enabled() || redissonClient == null || targetBucket == null || targetBucket.getId() == null) {
                return TransferResult.skipped();
            }
            int configuredSize = properties.getBucket().getTransfer().getSize();
            if (configuredSize <= 0) {
                return TransferResult.skipped();
            }

            RLock lock = redissonClient.getLock(lockKey(activityId, skuId, targetBucket.getId()));
            boolean locked;
            Timer.Sample lockSample = Timer.start(meterRegistry);
            try {
                locked = tryLock(lock);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return TransferResult.skipped();
            } finally {
                lockSample.stop(lockWaitTimer);
            }
            if (!locked) {
                return TransferResult.skipped();
            }
            try {
                return doTransfer(requestId, activityId, skuId, targetBucket, configuredSize);
            } finally {
                unlockIfHeldByCurrentThread(lock);
            }
        } finally {
            sample.stop(totalTimer);
        }
    }

    private TransferResult doTransfer(String requestId,
                                      Long activityId,
                                      Long skuId,
                                      SeckillStockBucketEntity targetBucket,
                                      int configuredSize) {
        SeckillStockBucketEntity source = bucketMapper.selectTransferSource(
                activityId,
                skuId,
                targetBucket.getId(),
                SOURCE_RESERVE_QUANTITY);
        if (source == null || source.getSaleableQuantity() == null) {
            return TransferResult.skipped();
        }
        int movableQuantity = Math.min(configuredSize, source.getSaleableQuantity() - SOURCE_RESERVE_QUANTITY);
        if (movableQuantity <= 0) {
            return TransferResult.skipped();
        }
        Timer.Sample moveSample = Timer.start(meterRegistry);
        try {
            int sourceUpdated = bucketMapper.deductTransferSource(
                    source.getId(),
                    source.getShardKey(),
                    movableQuantity,
                    SOURCE_RESERVE_QUANTITY);
            if (sourceUpdated == 0) {
                return TransferResult.skipped();
            }
            int targetUpdated = bucketMapper.addTransferTarget(targetBucket.getId(), targetBucket.getShardKey(), movableQuantity);
            if (targetUpdated == 0) {
                throw new BusinessException(409, "Seckill bucket transfer target update failed");
            }
        } finally {
            moveSample.stop(dbMoveTimer);
        }

        SeckillStockBucketEntity sourceAfter = bucketMapper.selectById(source.getId());
        SeckillStockBucketEntity targetAfter = bucketMapper.selectById(targetBucket.getId());
        if (sourceAfter == null || targetAfter == null) {
            throw new BusinessException(409, "Seckill bucket transfer bucket missing");
        }
        changeLogInsertTimer.record(() -> {
            changeLogMapper.insert(changeLog(requestId, activityId, skuId, sourceAfter, CHANGE_TRANSFER_OUT, -movableQuantity));
            changeLogMapper.insert(changeLog(requestId, activityId, skuId, targetAfter, CHANGE_TRANSFER_IN, movableQuantity));
        });
        signalPossiblyAvailable(activityId, skuId, targetAfter);
        if (sourceAfter.getSaleableQuantity() != null && sourceAfter.getSaleableQuantity() <= 0) {
            int updated = bucketMapper.markEmptyIfNoSaleable(sourceAfter.getId(), sourceAfter.getShardKey());
            if (updated > 0) {
                signalPossiblyEmpty(activityId, skuId, sourceAfter);
            }
        }
        return TransferResult.transferred(movableQuantity);
    }

    private void signalPossiblyAvailable(Long activityId, Long skuId, SeckillStockBucketEntity bucket) {
        if (availabilityCoordinator != null) {
            availabilityCoordinator.signalPossiblyAvailable(
                    activityId,
                    skuId,
                    bucket.getId(),
                    bucket.getBucketNo(),
                    bucket.getShardKey());
        }
    }

    private void signalPossiblyEmpty(Long activityId, Long skuId, SeckillStockBucketEntity bucket) {
        if (availabilityCoordinator != null) {
            availabilityCoordinator.signalPossiblyEmpty(
                    activityId,
                    skuId,
                    bucket.getId(),
                    bucket.getBucketNo(),
                    bucket.getShardKey());
        }
    }

    private boolean enabled() {
        return properties.getBucket().getTransfer().isEnabled();
    }

    int maxAttempts() {
        return enabled() ? Math.max(0, properties.getBucket().getTransfer().getMaxAttempts()) : 0;
    }

    private boolean tryLock(RLock lock) throws InterruptedException {
        SeckillProperties.Transfer transfer = properties.getBucket().getTransfer();
        long waitMillis = Math.max(0, transfer.getLockWaitMillis());
        long leaseMillis = transfer.getLockLeaseMillis();
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
            // A late lock expiry should not turn a skipped transfer path into a submit failure.
        }
    }

    private String lockKey(Long activityId, Long skuId, Long targetBucketId) {
        return "seckill:bucket:transfer:" + activityId + ":" + skuId + ":" + targetBucketId;
    }

    private Timer timer(String name, String description) {
        return Timer.builder(name)
                .description(description)
                .register(meterRegistry);
    }

    private SeckillStockChangeLogEntity changeLog(String requestId,
                                                  Long activityId,
                                                  Long skuId,
                                                  SeckillStockBucketEntity bucket,
                                                  String changeType,
                                                  int quantityDelta) {
        LocalDateTime now = LocalDateTime.now();
        SeckillStockChangeLogEntity changeLog = new SeckillStockChangeLogEntity();
        changeLog.setRequestId(requestId);
        changeLog.setActivityId(activityId);
        changeLog.setSkuId(skuId);
        changeLog.setBucketId(bucket.getId());
        changeLog.setBucketNo(bucket.getBucketNo());
        changeLog.setBucketShardKey(bucket.getShardKey());
        changeLog.setChangeType(changeType);
        changeLog.setQuantityDelta(quantityDelta);
        changeLog.setAfterQuantity(bucket.getSaleableQuantity());
        changeLog.setStatus(CHANGE_STATUS_NEW);
        changeLog.setCreatedAt(now);
        changeLog.setUpdatedAt(now);
        return changeLog;
    }

    public record TransferResult(boolean transferred, int quantity) {

        static TransferResult skipped() {
            return new TransferResult(false, 0);
        }

        static TransferResult transferred(int quantity) {
            return new TransferResult(true, quantity);
        }
    }
}

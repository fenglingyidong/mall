package com.mall.seckill.service.impl;

import com.mall.common.exception.BusinessException;
import com.mall.seckill.config.SeckillProperties;
import com.mall.seckill.mapper.SeckillBucketConfigMapper;
import com.mall.seckill.mapper.SeckillStockBucketMapper;
import com.mall.seckill.mapper.SeckillStockChangeLogMapper;
import com.mall.seckill.pojo.entity.SeckillBucketConfigEntity;
import com.mall.seckill.pojo.entity.SeckillStockBucketEntity;
import com.mall.seckill.pojo.entity.SeckillStockChangeLogEntity;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Component
public class SeckillBucketAutoTransferService {

    private final SeckillStockBucketMapper bucketMapper;
    private final SeckillBucketConfigMapper configMapper;
    private final SeckillStockChangeLogMapper changeLogMapper;
    private final SeckillProperties properties;
    private final SeckillBucketAvailabilityCoordinator availabilityCoordinator;
    private final Counter attemptCounter;
    private final Counter successCounter;
    private final Counter targetMissCounter;
    private final Counter sourceMissCounter;
    private final Counter sourceDeductLostCounter;
    private final Counter disabledCounter;
    private final Counter failureCounter;

    public SeckillBucketAutoTransferService(SeckillStockBucketMapper bucketMapper,
                                            SeckillBucketConfigMapper configMapper,
                                            SeckillStockChangeLogMapper changeLogMapper,
                                            SeckillProperties properties,
                                            MeterRegistry meterRegistry) {
        this(bucketMapper, configMapper, changeLogMapper, properties, meterRegistry,
                (SeckillBucketAvailabilityCoordinator) null);
    }

    @Autowired
    public SeckillBucketAutoTransferService(SeckillStockBucketMapper bucketMapper,
                                            SeckillBucketConfigMapper configMapper,
                                            SeckillStockChangeLogMapper changeLogMapper,
                                            SeckillProperties properties,
                                            MeterRegistry meterRegistry,
                                            ObjectProvider<SeckillBucketAvailabilityCoordinator> availabilityCoordinator) {
        this(bucketMapper,
                configMapper,
                changeLogMapper,
                properties,
                meterRegistry,
                availabilityCoordinator == null ? null : availabilityCoordinator.getIfAvailable());
    }

    SeckillBucketAutoTransferService(SeckillStockBucketMapper bucketMapper,
                                     SeckillBucketConfigMapper configMapper,
                                     SeckillStockChangeLogMapper changeLogMapper,
                                     SeckillProperties properties,
                                     MeterRegistry meterRegistry,
                                     SeckillBucketAvailabilityCoordinator availabilityCoordinator) {
        this.bucketMapper = bucketMapper;
        this.configMapper = configMapper;
        this.changeLogMapper = changeLogMapper;
        this.properties = properties;
        this.availabilityCoordinator = availabilityCoordinator;
        this.attemptCounter = counter(
                meterRegistry,
                "seckill.bucket.auto.transfer.attempt",
                "Seckill bucket auto transfer attempt count");
        this.successCounter = counter(
                meterRegistry,
                "seckill.bucket.auto.transfer.success",
                "Seckill bucket auto transfer success count");
        this.targetMissCounter = counter(
                meterRegistry,
                "seckill.bucket.auto.transfer.target.miss",
                "Seckill bucket auto transfer target miss count");
        this.sourceMissCounter = counter(
                meterRegistry,
                "seckill.bucket.auto.transfer.source.miss",
                "Seckill bucket auto transfer source miss count");
        this.sourceDeductLostCounter = counter(
                meterRegistry,
                "seckill.bucket.auto.transfer.source.deduct.lost",
                "Seckill bucket auto transfer source deduct lost race count");
        this.disabledCounter = counter(
                meterRegistry,
                "seckill.bucket.auto.transfer.disabled",
                "Seckill bucket auto transfer disabled skip count");
        this.failureCounter = counter(
                meterRegistry,
                "seckill.bucket.auto.transfer.failure",
                "Seckill bucket auto transfer failure count");
    }

    @Transactional(rollbackFor = Exception.class)
    public AutoTransferResult transferOnce(SeckillBucketConfigEntity config) {
        attemptCounter.increment();
        try {
            if (!enabled() || config == null || config.getActivityId() == null || config.getSkuId() == null) {
                return record(AutoTransferResult.disabled());
            }
            int transferSize = transferSize();
            if (transferSize <= 0) {
                return record(AutoTransferResult.disabled());
            }
            int reserveQuantity = sourceReserveQuantity();
            SeckillStockBucketEntity target = bucketMapper.selectAutoTransferTarget(
                    config.getActivityId(),
                    config.getSkuId(),
                    lowWatermark());
            if (target == null || target.getId() == null) {
                return record(AutoTransferResult.targetMiss());
            }
            SeckillStockBucketEntity source = bucketMapper.selectAutoTransferSource(
                    config.getActivityId(),
                    config.getSkuId(),
                    target.getId(),
                    transferSize,
                    reserveQuantity);
            if (source == null || source.getId() == null || source.getSaleableQuantity() == null) {
                return record(AutoTransferResult.sourceMiss());
            }
            int movableQuantity = Math.min(transferSize, source.getSaleableQuantity() - reserveQuantity);
            if (movableQuantity <= 0) {
                return record(AutoTransferResult.sourceMiss());
            }
            int sourceUpdated = bucketMapper.deductTransferSource(source.getId(), source.getShardKey(), movableQuantity, reserveQuantity);
            if (sourceUpdated == 0) {
                return record(AutoTransferResult.sourceDeductLost());
            }
            int targetUpdated = bucketMapper.addTransferTarget(target.getId(), target.getShardKey(), movableQuantity);
            if (targetUpdated == 0) {
                throw new BusinessException(409, "Seckill bucket auto transfer target update failed");
            }

            SeckillStockBucketEntity sourceAfter = bucketMapper.selectById(source.getId());
            SeckillStockBucketEntity targetAfter = bucketMapper.selectById(target.getId());
            if (sourceAfter == null || targetAfter == null) {
                throw new BusinessException(409, "Seckill bucket auto transfer bucket missing");
            }
            String requestId = requestId();
            changeLogMapper.insert(changeLog(
                    requestId,
                    config.getActivityId(),
                    config.getSkuId(),
                    sourceAfter,
                    SeckillBucketTransferService.CHANGE_TRANSFER_OUT,
                    -movableQuantity));
            changeLogMapper.insert(changeLog(
                    requestId,
                    config.getActivityId(),
                    config.getSkuId(),
                    targetAfter,
                    SeckillBucketTransferService.CHANGE_TRANSFER_IN,
                    movableQuantity));
            signalPossiblyAvailable(config.getActivityId(), config.getSkuId(), targetAfter);
            if (sourceAfter.getSaleableQuantity() != null && sourceAfter.getSaleableQuantity() <= 0) {
                int emptied = bucketMapper.markEmptyIfNoSaleable(sourceAfter.getId(), sourceAfter.getShardKey());
                if (emptied > 0) {
                    signalPossiblyEmpty(config.getActivityId(), config.getSkuId(), sourceAfter);
                }
            }
            return record(AutoTransferResult.transferred(movableQuantity));
        } catch (RuntimeException exception) {
            failureCounter.increment();
            throw exception;
        }
    }

    private boolean enabled() {
        return properties.getBucket().getAutoTransfer().isEnabled();
    }

    private int lowWatermark() {
        return Math.max(0, properties.getBucket().getAutoTransfer().getLowWatermark());
    }

    private int transferSize() {
        return Math.max(0, properties.getBucket().getAutoTransfer().getTransferSize());
    }

    private int sourceReserveQuantity() {
        return Math.max(0, properties.getBucket().getAutoTransfer().getSourceReserveQuantity());
    }

    private String requestId() {
        return "auto-transfer-" + UUID.randomUUID();
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

    private AutoTransferResult record(AutoTransferResult result) {
        switch (result.outcome()) {
            case DISABLED -> disabledCounter.increment();
            case TARGET_MISS -> targetMissCounter.increment();
            case SOURCE_MISS -> sourceMissCounter.increment();
            case SOURCE_DEDUCT_LOST -> sourceDeductLostCounter.increment();
            case TRANSFERRED -> successCounter.increment();
        }
        return result;
    }

    private Counter counter(MeterRegistry meterRegistry, String name, String description) {
        return Counter.builder(name)
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
        changeLog.setStatus(SeckillBucketTransferService.CHANGE_STATUS_NEW);
        changeLog.setCreatedAt(now);
        changeLog.setUpdatedAt(now);
        return changeLog;
    }

    public record AutoTransferResult(AutoTransferOutcome outcome, int quantity) {

        static AutoTransferResult disabled() {
            return new AutoTransferResult(AutoTransferOutcome.DISABLED, 0);
        }

        static AutoTransferResult targetMiss() {
            return new AutoTransferResult(AutoTransferOutcome.TARGET_MISS, 0);
        }

        static AutoTransferResult sourceMiss() {
            return new AutoTransferResult(AutoTransferOutcome.SOURCE_MISS, 0);
        }

        static AutoTransferResult sourceDeductLost() {
            return new AutoTransferResult(AutoTransferOutcome.SOURCE_DEDUCT_LOST, 0);
        }

        static AutoTransferResult transferred(int quantity) {
            return new AutoTransferResult(AutoTransferOutcome.TRANSFERRED, quantity);
        }

        boolean transferred() {
            return outcome == AutoTransferOutcome.TRANSFERRED;
        }
    }

    public enum AutoTransferOutcome {
        DISABLED,
        TARGET_MISS,
        SOURCE_MISS,
        SOURCE_DEDUCT_LOST,
        TRANSFERRED
    }
}

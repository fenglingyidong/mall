package com.mall.seckill.service.impl;

import com.mall.seckill.mapper.SeckillStockBucketMapper;
import com.mall.seckill.mapper.SeckillStockChangeLogMapper;
import com.mall.seckill.mapper.SeckillStockNotEnoughException;
import com.mall.seckill.mapper.SeckillStockSnapshotMapper;
import com.mall.seckill.pojo.entity.SeckillStockChangeLogEntity;
import com.mall.seckill.pojo.entity.SeckillStockSnapshotEntity;
import com.mall.seckill.pojo.vo.StockVersion;
import com.mall.seckill.service.event.SeckillDeductCommittedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

@Component
public class SeckillEntryFactWriter {

    private static final int MESSAGE_MAX_LENGTH = 255;
    private static final String SNAPSHOT_STATUS_REGISTERED = "REGISTERED";
    private static final String SNAPSHOT_MESSAGE_REGISTERED = "Registered";
    private static final String CHANGE_TYPE_DEDUCT = "DEDUCT";
    private static final String CHANGE_LOG_STATUS_NEW = "NEW";
    private static final int AFTER_QUANTITY_UNKNOWN = -1;

    private final SeckillStockSnapshotMapper snapshotMapper;
    private final SeckillStockBucketMapper bucketMapper;
    private final SeckillStockChangeLogMapper changeLogMapper;
    private final SeckillBucketService bucketService;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate snapshotTransaction;
    private final TransactionTemplate deductionTransaction;
    private final Timer totalTimer;
    private final Timer snapshotInsertTimer;
    private final Timer bucketDeductTimer;
    private final Timer changeLogInsertTimer;

    @Autowired
    public SeckillEntryFactWriter(SeckillStockSnapshotMapper snapshotMapper,
                                  SeckillStockBucketMapper bucketMapper,
                                  SeckillStockChangeLogMapper changeLogMapper,
                                  SeckillBucketService bucketService,
                                  ApplicationEventPublisher eventPublisher,
                                  MeterRegistry meterRegistry,
                                  PlatformTransactionManager transactionManager) {
        this.snapshotMapper = snapshotMapper;
        this.bucketMapper = bucketMapper;
        this.changeLogMapper = changeLogMapper;
        this.bucketService = bucketService;
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;
        this.snapshotTransaction = requiresNew(transactionManager);
        this.deductionTransaction = requiresNew(transactionManager);
        this.totalTimer = timer("seckill.entry.fact.total", "Official submit entry fact write total latency");
        this.snapshotInsertTimer = timer("seckill.submit.record.snapshot.insert", "Official submit stock snapshot insert latency");
        this.bucketDeductTimer = timer("seckill.submit.record.bucket.db.deduct", "Official submit bucket conditional deduct SQL latency");
        this.changeLogInsertTimer = timer("seckill.submit.record.bucket.change-log.insert", "Official submit bucket stock change log insert latency");
    }

    /**
     * 写入入口事实的主流程。
     *
     * <p>先独立提交 snapshot，保留后续回补、幂等查询和失败修复依据；
     * 再在另一个事务里同时提交 bucket 扣减和 DEDUCT change_log。</p>
     */
    public EntryFactResult recordAcceptedEntry(EntryFactCommand command) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            EntryFactResult snapshotResult = registerSnapshot(command);
            if (snapshotResult.outcome() != EntryFactOutcome.CREATED) {
                return snapshotResult;
            }
            try {
                return recordDeductionFact(command, snapshotResult.snapshot());
            } catch (SeckillStockNotEnoughException exception) {
                markRegisteredSnapshotFailed(command.requestId(), "Stock not enough");
                return EntryFactResult.stockNotEnough(snapshotResult.snapshot(), command.selectedBucket());
            } catch (DuplicateKeyException exception) {
                markRegisteredSnapshotFailed(command.requestId(), "Duplicate purchase");
                return EntryFactResult.deductDuplicate(snapshotResult.snapshot(), command.selectedBucket());
            }
        } finally {
            sample.stop(totalTimer);
        }
    }

    /**
     * 登记 request snapshot。
     *
     * <p>这里采用先 INSERT、冲突后 SELECT 的模式，正常路径少一次幂等读；
     * 主键冲突表示同一 requestId 已登记过，唯一键冲突但查不到主键时按买家活跃占位冲突处理。</p>
     */
    private EntryFactResult registerSnapshot(EntryFactCommand command) {
        try {
            return snapshotTransaction.execute(status -> {
                SeckillStockSnapshotEntity snapshot = snapshot(command);
                snapshotInsertTimer.record(() -> snapshotMapper.insert(snapshot));
                return EntryFactResult.created(toSnapshot(snapshot), command.selectedBucket(), null, null);
            });
        } catch (DuplicateKeyException exception) {
            SeckillStockSnapshotEntity duplicate = snapshotMapper.selectById(command.requestId());
            if (duplicate != null) {
                return EntryFactResult.requestDuplicate(toSnapshot(duplicate), command.selectedBucket());
            }
            return EntryFactResult.activeDuplicate(command.selectedBucket());
        }
    }

    /**
     * 在同一个事务内写入库存扣减事实。
     *
     * <p>bucket 条件扣减成功后才插入 change_log；任一步失败都回滚本事务，
     * 由外层把已登记的 snapshot 标为 FAILED 并释放 active_key。</p>
     */
    private EntryFactResult recordDeductionFact(EntryFactCommand command, EntryStockSnapshot snapshot) {
        return deductionTransaction.execute(status -> {
            SeckillStockChangeLogEntity changeLog = deductSelectedBucketAndRecordChangeLog(command);
            if (changeLog != null) {
                StockVersion stockVersion = bucketService.hotPathStockVersion(command.activityId(), command.skuId());
                return EntryFactResult.created(snapshot, command.selectedBucket(), stockVersion, changeLog.getId());
            }

            var exhaustedBucket = bucketService.markBucketEmptyIfExhausted(
                    command.activityId(),
                    command.skuId(),
                    command.selectedBucket().bucketId(),
                    command.selectedBucket().bucketShardKey());
            if (bucketService.maxTransferAttempts() > 0
                    && bucketService.shouldAttemptRequestTransfer(
                    command.activityId(),
                    command.skuId(),
                    command.selectedBucket().bucketId())
                    && bucketService.tryTransfer(command.requestId(), command.activityId(), command.skuId(), exhaustedBucket)) {
                changeLog = deductSelectedBucketAndRecordChangeLog(command);
                if (changeLog != null) {
                    StockVersion stockVersion = bucketService.hotPathStockVersion(command.activityId(), command.skuId());
                    return EntryFactResult.created(snapshot, command.selectedBucket(), stockVersion, changeLog.getId());
                }
            }
            bucketService.markBucketEmptyIfExhausted(
                    command.activityId(),
                    command.skuId(),
                    command.selectedBucket().bucketId(),
                    command.selectedBucket().bucketShardKey());
            throw new SeckillStockNotEnoughException();
        });
    }

    /**
     * 单次尝试扣减已选业务桶并记录 DEDUCT change_log。
     *
     * <p>UPDATE 返回 0 表示该桶在选择后被并发扣空或状态变化，调用方会进入耗尽桶标记和搬桶兜底分支。</p>
     */
    private SeckillStockChangeLogEntity deductSelectedBucketAndRecordChangeLog(EntryFactCommand command) {
        SeckillBucketService.SelectedBucket selectedBucket = command.selectedBucket();
        int updated = bucketDeductTimer.record(() ->
                bucketMapper.deductSaleableAndIncreaseVersionByShard(
                        selectedBucket.bucketId(),
                        selectedBucket.bucketShardKey(),
                        command.quantity()));
        if (updated <= 0) {
            return null;
        }
        SeckillStockChangeLogEntity changeLog = changeLog(command);
        changeLogInsertTimer.record(() -> changeLogMapper.insert(changeLog));
        publishDeductCommitted(command.requestId(), selectedBucket.bucketShardKey());
        return changeLog;
    }

    private SeckillStockSnapshotEntity snapshot(EntryFactCommand command) {
        SeckillBucketService.SelectedBucket selectedBucket = command.selectedBucket();
        LocalDateTime now = LocalDateTime.now();
        SeckillStockSnapshotEntity snapshot = new SeckillStockSnapshotEntity();
        snapshot.setRequestId(command.requestId());
        snapshot.setStockId(command.stockId());
        snapshot.setBucketId(selectedBucket.bucketId());
        snapshot.setBucketNo(selectedBucket.bucketNo());
        snapshot.setBucketShardKey(selectedBucket.bucketShardKey());
        snapshot.setStrategyVersion(selectedBucket.strategyVersion());
        snapshot.setActivityId(command.activityId());
        snapshot.setSkuId(command.skuId());
        snapshot.setUserId(command.userId());
        snapshot.setActiveKey(command.userId());
        snapshot.setQuantity(command.quantity());
        snapshot.setStatus(SNAPSHOT_STATUS_REGISTERED);
        snapshot.setMessage(SNAPSHOT_MESSAGE_REGISTERED);
        snapshot.setCreatedAt(now);
        snapshot.setUpdatedAt(now);
        return snapshot;
    }

    private SeckillStockChangeLogEntity changeLog(EntryFactCommand command) {
        SeckillBucketService.SelectedBucket selectedBucket = command.selectedBucket();
        LocalDateTime now = LocalDateTime.now();
        SeckillStockChangeLogEntity changeLog = new SeckillStockChangeLogEntity();
        changeLog.setRequestId(command.requestId());
        changeLog.setActivityId(command.activityId());
        changeLog.setSkuId(command.skuId());
        changeLog.setBucketId(selectedBucket.bucketId());
        changeLog.setBucketNo(selectedBucket.bucketNo());
        changeLog.setBucketShardKey(selectedBucket.bucketShardKey());
        changeLog.setChangeType(CHANGE_TYPE_DEDUCT);
        changeLog.setQuantityDelta(-command.quantity());
        changeLog.setAfterQuantity(AFTER_QUANTITY_UNKNOWN);
        changeLog.setStatus(CHANGE_LOG_STATUS_NEW);
        changeLog.setCreatedAt(now);
        changeLog.setUpdatedAt(now);
        return changeLog;
    }

    private void publishDeductCommitted(String requestId, Long bucketShardKey) {
        if (eventPublisher == null || requestId == null || requestId.isBlank() || bucketShardKey == null) {
            return;
        }
        eventPublisher.publishEvent(new SeckillDeductCommittedEvent(requestId, bucketShardKey));
    }

    /**
     * 修正已经独立提交但扣减事实未成功落库的 snapshot。
     */
    private void markRegisteredSnapshotFailed(String requestId, String message) {
        snapshotMapper.releaseActiveKeyIfRegistered(requestId, truncate(message, MESSAGE_MAX_LENGTH));
    }

    private EntryStockSnapshot toSnapshot(SeckillStockSnapshotEntity entity) {
        return new EntryStockSnapshot(
                entity.getRequestId(),
                entity.getActivityId(),
                entity.getSkuId(),
                entity.getUserId(),
                entity.getQuantity(),
                entity.getStatus());
    }

    private TransactionTemplate requiresNew(PlatformTransactionManager transactionManager) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transactionTemplate;
    }

    private Timer timer(String name, String description) {
        return Timer.builder(name)
                .description(description)
                .register(meterRegistry);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    public record EntryFactCommand(String requestId,
                                   Long stockId,
                                   Long activityId,
                                   Long skuId,
                                   Long userId,
                                   int quantity,
                                   SeckillBucketService.SelectedBucket selectedBucket) {
    }

    public record EntryFactResult(EntryFactOutcome outcome,
                                  StockVersion stockVersion,
                                  EntryStockSnapshot snapshot,
                                  SeckillBucketService.SelectedBucket selectedBucket,
                                  Long changeLogId) {

        static EntryFactResult created(EntryStockSnapshot snapshot,
                                       SeckillBucketService.SelectedBucket selectedBucket,
                                       StockVersion stockVersion,
                                       Long changeLogId) {
            return new EntryFactResult(EntryFactOutcome.CREATED, stockVersion, snapshot, selectedBucket, changeLogId);
        }

        static EntryFactResult requestDuplicate(EntryStockSnapshot snapshot,
                                                SeckillBucketService.SelectedBucket selectedBucket) {
            return new EntryFactResult(EntryFactOutcome.REQUEST_DUPLICATE, null, snapshot, selectedBucket, null);
        }

        static EntryFactResult activeDuplicate(SeckillBucketService.SelectedBucket selectedBucket) {
            return new EntryFactResult(EntryFactOutcome.ACTIVE_DUPLICATE, null, null, selectedBucket, null);
        }

        static EntryFactResult stockNotEnough(EntryStockSnapshot snapshot,
                                              SeckillBucketService.SelectedBucket selectedBucket) {
            return new EntryFactResult(EntryFactOutcome.STOCK_NOT_ENOUGH, null, snapshot, selectedBucket, null);
        }

        static EntryFactResult deductDuplicate(EntryStockSnapshot snapshot,
                                               SeckillBucketService.SelectedBucket selectedBucket) {
            return new EntryFactResult(EntryFactOutcome.DEDUCT_DUPLICATE, null, snapshot, selectedBucket, null);
        }
    }

    public enum EntryFactOutcome {
        CREATED,
        REQUEST_DUPLICATE,
        ACTIVE_DUPLICATE,
        STOCK_NOT_ENOUGH,
        DEDUCT_DUPLICATE
    }

    public record EntryStockSnapshot(String requestId,
                                     Long activityId,
                                     Long skuId,
                                     Long userId,
                                     Integer quantity,
                                     String status) {
    }
}

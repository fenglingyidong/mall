package com.mall.seckill.service.impl;

import com.mall.seckill.config.SeckillProperties;
import com.mall.seckill.mapper.SeckillRepository;
import com.mall.seckill.mapper.SeckillStockChangeLogMapper;
import com.mall.seckill.mapper.SeckillStockSnapshotMapper;
import com.mall.seckill.pojo.entity.SeckillStockSnapshotEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "mall.seckill.snapshot-repair", name = "enabled", havingValue = "true")
public class SeckillSnapshotFactRepairJob {

    private static final Logger log = LoggerFactory.getLogger(SeckillSnapshotFactRepairJob.class);
    private static final int MIN_BATCH_SIZE = 1;
    private static final int MAX_BATCH_SIZE = 1000;
    private static final String CHANGE_TYPE_DEDUCT = "DEDUCT";
    private static final String DEDUCTION_FACT_MISSING = "Deduction fact missing";

    private final SeckillStockSnapshotMapper snapshotMapper;
    private final SeckillStockChangeLogMapper changeLogMapper;
    private final SeckillRepository repository;
    private final SeckillProperties properties;

    public SeckillSnapshotFactRepairJob(SeckillStockSnapshotMapper snapshotMapper,
                                        SeckillStockChangeLogMapper changeLogMapper,
                                        SeckillRepository repository,
                                        SeckillProperties properties) {
        this.snapshotMapper = snapshotMapper;
        this.changeLogMapper = changeLogMapper;
        this.repository = repository;
        this.properties = properties == null ? new SeckillProperties() : properties;
    }

    @Scheduled(fixedDelayString = "${mall.seckill.snapshot-repair.fixed-delay:1000}")
    public void repair() {
        SeckillProperties.SnapshotRepair config = properties.getSnapshotRepair();
        if (!config.isEnabled()) {
            return;
        }

        int batchSize = Math.max(MIN_BATCH_SIZE, Math.min(config.getBatchSize(), MAX_BATCH_SIZE));
        long timeoutSeconds = Math.max(1L, config.getRegisteredTimeoutSeconds());
        LocalDateTime before = LocalDateTime.now().minusSeconds(timeoutSeconds);
        List<SeckillStockSnapshotEntity> snapshots = snapshotMapper.findRegisteredBefore(before, batchSize);
        if (snapshots == null) {
            return;
        }

        for (SeckillStockSnapshotEntity snapshot : snapshots) {
            try {
                repairOne(snapshot);
            } catch (RuntimeException exception) {
                log.warn("Failed to repair seckill stock snapshot, requestId={}",
                        snapshot == null ? null : snapshot.getRequestId(),
                        exception);
            }
        }
    }

    private void repairOne(SeckillStockSnapshotEntity snapshot) {
        if (snapshot == null) {
            return;
        }
        String requestId = snapshot.getRequestId();
        Long bucketShardKey = snapshot.getBucketShardKey();
        if (requestId == null || requestId.isBlank()) {
            log.warn("Skip registered seckill snapshot repair without request id");
            return;
        }
        if (bucketShardKey == null) {
            log.warn("Skip registered seckill snapshot repair without bucket shard key, requestId={}", requestId);
            return;
        }

        long deductFacts = changeLogMapper.countByRequestIdAndChangeTypeAndBucketShardKey(
                requestId,
                CHANGE_TYPE_DEDUCT,
                bucketShardKey);
        if (deductFacts > 0) {
            return;
        }

        repository.failRegisteredSnapshotIfPresent(requestId, bucketShardKey, DEDUCTION_FACT_MISSING);
    }
}

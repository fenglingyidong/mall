package com.mall.seckill.service.impl;

import com.mall.seckill.config.SeckillProperties;
import com.mall.seckill.mapper.SeckillStockChangeLogMapper;
import com.mall.seckill.pojo.entity.SeckillStockChangeLogEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(prefix = "mall.seckill.bucket.center-ledger", name = "enabled", havingValue = "true")
public class SeckillCenterBucketLedgerConsumer {

    private static final Logger log = LoggerFactory.getLogger(SeckillCenterBucketLedgerConsumer.class);
    private static final int MAX_BATCH_SIZE = 1000;

    private final SeckillStockChangeLogMapper changeLogMapper;
    private final SeckillCenterBucketLedgerApplier applier;
    private final SeckillProperties properties;

    public SeckillCenterBucketLedgerConsumer(SeckillStockChangeLogMapper changeLogMapper,
                                             SeckillCenterBucketLedgerApplier applier,
                                             SeckillProperties properties) {
        this.changeLogMapper = changeLogMapper;
        this.applier = applier;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${mall.seckill.bucket.center-ledger.fixed-delay:1000}")
    public void consume() {
        List<String> sourceStatuses = sourceStatuses();
        int batchSize = batchSize();
        List<Long> bucketShardKeys = properties.getBucket().getRouting().getBucketShardKeys();
        if (bucketShardKeys == null || bucketShardKeys.isEmpty()) {
            for (String sourceStatus : sourceStatuses) {
                consume(changeLogMapper.selectByStatusForConsume(sourceStatus, batchSize));
            }
            return;
        }
        for (String sourceStatus : sourceStatuses) {
            for (Long bucketShardKey : bucketShardKeys) {
                consume(changeLogMapper.selectByStatusForConsumeByShard(
                        bucketShardKey,
                        sourceStatus,
                        batchSize));
            }
        }
    }

    private List<String> sourceStatuses() {
        if (properties.getOrderOutbox().isEnabled()) {
            return List.of(SeckillStockChangeLogStatus.OUTBOXED);
        }
        // Rollback fallback: drain logs already outboxed before legacy NEW logs.
        return List.of(SeckillStockChangeLogStatus.OUTBOXED, SeckillStockChangeLogStatus.NEW);
    }

    private void consume(List<SeckillStockChangeLogEntity> changeLogs) {
        for (List<SeckillStockChangeLogEntity> group : groupByLedger(changeLogs).values()) {
            try {
                applier.apply(group);
            } catch (RuntimeException exception) {
                log.warn("Failed to apply seckill center bucket ledger, changeLogIds={}",
                        changeLogIds(group),
                        exception);
            }
        }
    }

    private int batchSize() {
        int configured = properties.getBucket().getCenterLedger().getBatchSize();
        return Math.max(1, Math.min(configured, MAX_BATCH_SIZE));
    }

    Map<LedgerKey, List<SeckillStockChangeLogEntity>> groupByLedger(List<SeckillStockChangeLogEntity> changeLogs) {
        Map<LedgerKey, List<SeckillStockChangeLogEntity>> groups = new LinkedHashMap<>();
        if (changeLogs == null) {
            return groups;
        }
        for (SeckillStockChangeLogEntity changeLog : changeLogs) {
            LedgerKey key = new LedgerKey(changeLog.getActivityId(), changeLog.getSkuId());
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(changeLog);
        }
        return groups;
    }

    private String changeLogIds(List<SeckillStockChangeLogEntity> changeLogs) {
        return changeLogs.stream()
                .map(SeckillStockChangeLogEntity::getId)
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    record LedgerKey(Long activityId, Long skuId) {
    }
}

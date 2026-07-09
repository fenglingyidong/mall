package com.mall.seckill.service.impl;

import com.mall.common.exception.BusinessException;
import com.mall.seckill.mapper.SeckillStockBucketMapper;
import com.mall.seckill.mapper.SeckillStockChangeLogMapper;
import com.mall.seckill.pojo.entity.SeckillStockChangeLogEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SeckillCenterBucketLedgerApplier {

    static final String STATUS_NEW = SeckillStockChangeLogStatus.NEW;
    static final String STATUS_PROCESSING = SeckillStockChangeLogStatus.LEDGER_PROCESSING;
    static final String STATUS_APPLIED = SeckillStockChangeLogStatus.APPLIED;

    private final SeckillStockChangeLogMapper changeLogMapper;
    private final SeckillStockBucketMapper bucketMapper;

    public SeckillCenterBucketLedgerApplier(SeckillStockChangeLogMapper changeLogMapper,
                                            SeckillStockBucketMapper bucketMapper) {
        this.changeLogMapper = changeLogMapper;
        this.bucketMapper = bucketMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean apply(Long changeLogId) {
        SeckillStockChangeLogEntity changeLog = changeLogMapper.selectById(changeLogId);
        if (changeLog == null) {
            return false;
        }
        return apply(List.of(changeLog)) > 0;
    }

    @Transactional(rollbackFor = Exception.class)
    public int apply(List<SeckillStockChangeLogEntity> changeLogs) {
        List<SeckillStockChangeLogEntity> claimedLogs = claim(changeLogs);
        if (claimedLogs.isEmpty()) {
            return 0;
        }
        Map<LedgerKey, Integer> deltas = aggregateDeltas(claimedLogs);
        for (Map.Entry<LedgerKey, Integer> entry : deltas.entrySet()) {
            if (entry.getValue() == 0) {
                continue;
            }
            int updated = bucketMapper.applyCenterQuantityDelta(
                    entry.getKey().activityId(),
                    entry.getKey().skuId(),
                    entry.getValue());
            if (updated == 0) {
                throw new BusinessException(409, "Seckill center bucket ledger apply failed");
            }
        }
        List<Long> claimedIds = claimedLogs.stream()
                .map(SeckillStockChangeLogEntity::getId)
                .toList();
        int applied = markApplied(claimedLogs);
        if (applied != claimedIds.size()) {
            throw new BusinessException(409, "Seckill stock change log status apply failed");
        }
        return claimedLogs.size();
    }

    private List<SeckillStockChangeLogEntity> claim(List<SeckillStockChangeLogEntity> changeLogs) {
        if (changeLogs == null || changeLogs.isEmpty()) {
            return List.of();
        }
        List<SeckillStockChangeLogEntity> claimedLogs = new ArrayList<>();
        for (SeckillStockChangeLogEntity changeLog : changeLogs) {
            String sourceStatus = claimableSourceStatus(changeLog);
            if (sourceStatus == null) {
                continue;
            }
            validate(changeLog);
            int claimed = updateStatus(changeLog, sourceStatus, STATUS_PROCESSING);
            if (claimed > 0) {
                claimedLogs.add(changeLog);
            }
        }
        return claimedLogs;
    }

    private String claimableSourceStatus(SeckillStockChangeLogEntity changeLog) {
        if (changeLog == null || changeLog.getId() == null) {
            return null;
        }
        String status = changeLog.getStatus();
        if (STATUS_NEW.equals(status) || SeckillStockChangeLogStatus.OUTBOXED.equals(status)) {
            return status;
        }
        return null;
    }

    private Map<LedgerKey, Integer> aggregateDeltas(List<SeckillStockChangeLogEntity> claimedLogs) {
        Map<LedgerKey, Integer> deltas = new LinkedHashMap<>();
        for (SeckillStockChangeLogEntity changeLog : claimedLogs) {
            LedgerKey key = new LedgerKey(changeLog.getActivityId(), changeLog.getSkuId());
            deltas.merge(key, changeLog.getQuantityDelta(), Integer::sum);
        }
        return deltas;
    }

    private void validate(SeckillStockChangeLogEntity changeLog) {
        if (changeLog.getActivityId() == null
                || changeLog.getSkuId() == null
                || changeLog.getQuantityDelta() == null) {
            throw new BusinessException(409, "Seckill stock change log missing ledger fields");
        }
    }

    private int updateStatus(SeckillStockChangeLogEntity changeLog, String expectedStatus, String nextStatus) {
        if (changeLog.getBucketShardKey() == null) {
            return changeLogMapper.updateStatus(changeLog.getId(), expectedStatus, nextStatus);
        }
        return changeLogMapper.updateStatusByShard(
                changeLog.getId(),
                changeLog.getBucketShardKey(),
                expectedStatus,
                nextStatus);
    }

    private int markApplied(List<SeckillStockChangeLogEntity> claimedLogs) {
        Map<Long, List<Long>> idsByShard = new LinkedHashMap<>();
        List<Long> legacyIds = new ArrayList<>();
        for (SeckillStockChangeLogEntity changeLog : claimedLogs) {
            if (changeLog.getBucketShardKey() == null) {
                legacyIds.add(changeLog.getId());
            } else {
                idsByShard.computeIfAbsent(changeLog.getBucketShardKey(), ignored -> new ArrayList<>())
                        .add(changeLog.getId());
            }
        }
        int applied = legacyIds.isEmpty()
                ? 0
                : changeLogMapper.updateStatusByIds(legacyIds, STATUS_PROCESSING, STATUS_APPLIED);
        for (Map.Entry<Long, List<Long>> entry : idsByShard.entrySet()) {
            applied += changeLogMapper.updateStatusByIdsAndShard(
                    entry.getValue(),
                    entry.getKey(),
                    STATUS_PROCESSING,
                    STATUS_APPLIED);
        }
        return applied;
    }

    private record LedgerKey(Long activityId, Long skuId) {
    }
}

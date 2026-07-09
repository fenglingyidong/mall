package com.mall.seckill.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.util.JsonUtils;
import com.mall.message.MessageNames;
import com.mall.message.ReliableMessagePublisher;
import com.mall.message.ReliableMessageRepository;
import com.mall.seckill.config.SeckillProperties;
import com.mall.seckill.mapper.SeckillRepository;
import com.mall.seckill.mapper.SeckillStockChangeLogMapper;
import com.mall.seckill.pojo.dto.SeckillOrderRequest;
import com.mall.seckill.pojo.entity.SeckillSku;
import com.mall.seckill.pojo.entity.SeckillStockChangeLogEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class SeckillOrderOutboxFromChangeLogService {

    private static final Logger log = LoggerFactory.getLogger(SeckillOrderOutboxFromChangeLogService.class);
    private static final int MIN_BATCH_SIZE = 1;
    private static final int MAX_BATCH_SIZE = 1000;
    private static final long OUTBOXING_STALE_SECONDS = 60L;
    private static final String CHANGE_TYPE_DEDUCT = "DEDUCT";

    private final SeckillStockChangeLogMapper changeLogMapper;
    private final SeckillRepository seckillRepository;
    private final ReliableMessageRepository messageRepository;
    private final ReliableMessagePublisher messagePublisher;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final SeckillProperties properties;

    public SeckillOrderOutboxFromChangeLogService(SeckillStockChangeLogMapper changeLogMapper,
                                                  SeckillRepository seckillRepository,
                                                  ReliableMessageRepository messageRepository,
                                                  ReliableMessagePublisher messagePublisher,
                                                  ObjectMapper objectMapper,
                                                  TransactionTemplate transactionTemplate,
                                                  SeckillProperties properties) {
        this.changeLogMapper = changeLogMapper;
        this.seckillRepository = seckillRepository;
        this.messageRepository = messageRepository;
        this.messagePublisher = messagePublisher;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
    }

    public int drainOnce() {
        SeckillProperties.OrderOutbox config = properties.getOrderOutbox();
        if (!config.isEnabled()) {
            return 0;
        }

        int batchSize = Math.min(MAX_BATCH_SIZE, Math.max(MIN_BATCH_SIZE, config.getBatchSize()));
        resetStaleOutboxing(batchSize);
        List<SeckillStockChangeLogEntity> changeLogs = changeLogMapper.selectByStatusForConsume(
                SeckillStockChangeLogStatus.NEW,
                batchSize);
        int drained = 0;
        for (SeckillStockChangeLogEntity changeLog : changeLogs) {
            if (drainOne(changeLog)) {
                drained++;
            }
        }
        return drained;
    }

    private void resetStaleOutboxing(int batchSize) {
        LocalDateTime before = LocalDateTime.now().minusSeconds(OUTBOXING_STALE_SECONDS);
        List<SeckillStockChangeLogEntity> staleChangeLogs = changeLogMapper.selectStaleIdsByStatus(
                SeckillStockChangeLogStatus.OUTBOXING,
                before,
                batchSize);
        if (staleChangeLogs == null) {
            return;
        }
        for (SeckillStockChangeLogEntity changeLog : staleChangeLogs) {
            if (changeLog.getBucketShardKey() == null) {
                log.warn("Skip stale seckill order outbox reset without bucket shard key, changeLogId={}",
                        changeLog.getId());
                continue;
            }
            resetStaleStatusByShard(changeLog, before);
        }
    }

    private boolean drainOne(SeckillStockChangeLogEntity changeLog) {
        if (changeLog.getBucketShardKey() == null) {
            log.warn("Skip seckill order outbox change log without bucket shard key, changeLogId={}, requestId={}",
                    changeLog.getId(), changeLog.getRequestId());
            return false;
        }
        ClaimedChangeLog claimed = claim(changeLog);
        if (claimed == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(transactionTemplate.execute(status -> processClaimed(claimed)));
        } catch (NonRetryableOutboxException exception) {
            log.warn("Failed to build seckill order outbox from non-retryable change log, changeLogId={}, requestId={}",
                    changeLog.getId(), changeLog.getRequestId(), exception);
            markFailed(claimed);
            return false;
        } catch (RuntimeException exception) {
            log.warn("Retryable failure while building seckill order outbox from change log, changeLogId={}, requestId={}",
                    changeLog.getId(), changeLog.getRequestId(), exception);
            return false;
        }
    }

    private ClaimedChangeLog claim(SeckillStockChangeLogEntity changeLog) {
        LocalDateTime claimedAt = LocalDateTime.now().withNano(0);
        int updated = changeLogMapper.claimStatusByShard(
                changeLog.getId(),
                changeLog.getBucketShardKey(),
                SeckillStockChangeLogStatus.NEW,
                SeckillStockChangeLogStatus.OUTBOXING,
                claimedAt);
        return updated > 0 ? new ClaimedChangeLog(changeLog, claimedAt) : null;
    }

    private boolean processClaimed(ClaimedChangeLog claimed) {
        SeckillStockChangeLogEntity changeLog = claimed.changeLog();
        if (!CHANGE_TYPE_DEDUCT.equals(changeLog.getChangeType())) {
            updateStatusOrThrow(claimed, SeckillStockChangeLogStatus.OUTBOXING, SeckillStockChangeLogStatus.OUTBOXED);
            return true;
        }
        return processDeduct(claimed);
    }

    private boolean processDeduct(ClaimedChangeLog claimed) {
        SeckillStockChangeLogEntity changeLog = claimed.changeLog();
        String requestId = changeLog.getRequestId();
        Long bucketShardKey = changeLog.getBucketShardKey();
        if (requestId == null || requestId.isBlank()) {
            throw new NonRetryableOutboxException("Seckill change log requestId is blank");
        }

        boolean outboxExists = messageRepository.existsByBusinessKeyAndRoutingKey(
                requestId,
                MessageNames.SECKILL_ORDER_CREATE_ROUTING_KEY,
                bucketShardKey);
        if (outboxExists) {
            updateStatusOrThrow(claimed, SeckillStockChangeLogStatus.OUTBOXING, SeckillStockChangeLogStatus.OUTBOXED);
            return true;
        }

        SeckillRepository.StockSnapshot snapshot = seckillRepository.findStockSnapshot(requestId, bucketShardKey);
        if (snapshot == null) {
            throw new IllegalStateException("Seckill stock snapshot not found");
        }

        SeckillSku sku = seckillRepository.requireSku(changeLog.getActivityId(), changeLog.getSkuId());
        SeckillOrderRequest orderRequest = new SeckillOrderRequest(
                requestId,
                changeLog.getActivityId(),
                snapshot.userId(),
                changeLog.getSkuId(),
                sku.skuName(),
                sku.price(),
                snapshot.quantity(),
                bucketShardKey);
        messagePublisher.enqueueSeckillOrderCreate(requestId, JsonUtils.toJson(objectMapper, orderRequest), bucketShardKey);
        updateStatusOrThrow(claimed, SeckillStockChangeLogStatus.OUTBOXING, SeckillStockChangeLogStatus.OUTBOXED);
        return true;
    }

    private void resetStaleStatusByShard(SeckillStockChangeLogEntity changeLog, LocalDateTime before) {
        changeLogMapper.resetStaleStatusByShard(
                changeLog.getId(),
                changeLog.getBucketShardKey(),
                SeckillStockChangeLogStatus.OUTBOXING,
                SeckillStockChangeLogStatus.NEW,
                before);
    }

    private boolean updateStatusByShardIfClaimed(ClaimedChangeLog claimed, String expectedStatus, String nextStatus) {
        SeckillStockChangeLogEntity changeLog = claimed.changeLog();
        int updated;
        updated = changeLogMapper.updateStatusByShardIfClaimed(
                changeLog.getId(),
                changeLog.getBucketShardKey(),
                expectedStatus,
                nextStatus,
                claimed.claimedAt());
        return updated > 0;
    }

    private void updateStatusOrThrow(ClaimedChangeLog claimed, String expectedStatus, String nextStatus) {
        if (!updateStatusByShardIfClaimed(claimed, expectedStatus, nextStatus)) {
            throw new IllegalStateException("Seckill change log status update failed, id="
                    + claimed.changeLog().getId() + ", expectedStatus=" + expectedStatus + ", nextStatus=" + nextStatus);
        }
    }

    private void markFailed(ClaimedChangeLog claimed) {
        try {
            boolean marked = updateStatusByShardIfClaimed(
                    claimed,
                    SeckillStockChangeLogStatus.OUTBOXING,
                    SeckillStockChangeLogStatus.OUTBOX_FAILED);
            if (!marked) {
                log.warn("Skipped stale seckill change log failure mark because claim changed, changeLogId={}, requestId={}",
                        claimed.changeLog().getId(), claimed.changeLog().getRequestId());
            }
        } catch (RuntimeException exception) {
            log.warn("Failed to mark seckill change log outbox failed, changeLogId={}, requestId={}",
                    claimed.changeLog().getId(), claimed.changeLog().getRequestId(), exception);
        }
    }

    private record ClaimedChangeLog(SeckillStockChangeLogEntity changeLog, LocalDateTime claimedAt) {
    }

    private static class NonRetryableOutboxException extends RuntimeException {

        NonRetryableOutboxException(String message) {
            super(message);
        }
    }
}

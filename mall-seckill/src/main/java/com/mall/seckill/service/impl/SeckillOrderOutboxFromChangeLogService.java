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

import java.util.List;

@Service
public class SeckillOrderOutboxFromChangeLogService {

    private static final Logger log = LoggerFactory.getLogger(SeckillOrderOutboxFromChangeLogService.class);
    private static final int MIN_BATCH_SIZE = 1;
    private static final int MAX_BATCH_SIZE = 1000;
    private static final String CHANGE_TYPE_DEDUCT = "DEDUCT";

    private final SeckillStockChangeLogMapper changeLogMapper;
    private final SeckillRepository seckillRepository;
    private final ReliableMessageRepository messageRepository;
    private final ReliableMessagePublisher messagePublisher;
    private final ObjectMapper objectMapper;
    private final SeckillProperties properties;

    public SeckillOrderOutboxFromChangeLogService(SeckillStockChangeLogMapper changeLogMapper,
                                                  SeckillRepository seckillRepository,
                                                  ReliableMessageRepository messageRepository,
                                                  ReliableMessagePublisher messagePublisher,
                                                  ObjectMapper objectMapper,
                                                  SeckillProperties properties) {
        this.changeLogMapper = changeLogMapper;
        this.seckillRepository = seckillRepository;
        this.messageRepository = messageRepository;
        this.messagePublisher = messagePublisher;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public int drainOnce() {
        SeckillProperties.OrderOutbox config = properties.getOrderOutbox();
        if (!config.isEnabled()) {
            return 0;
        }

        int batchSize = Math.min(MAX_BATCH_SIZE, Math.max(MIN_BATCH_SIZE, config.getBatchSize()));
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

    private boolean drainOne(SeckillStockChangeLogEntity changeLog) {
        if (!updateStatus(changeLog, SeckillStockChangeLogStatus.NEW, SeckillStockChangeLogStatus.OUTBOXING)) {
            return false;
        }
        try {
            if (!CHANGE_TYPE_DEDUCT.equals(changeLog.getChangeType())) {
                return updateStatus(changeLog, SeckillStockChangeLogStatus.OUTBOXING, SeckillStockChangeLogStatus.OUTBOXED);
            }
            return drainDeduct(changeLog);
        } catch (RuntimeException exception) {
            log.warn("Failed to build seckill order outbox from change log, changeLogId={}, requestId={}",
                    changeLog.getId(), changeLog.getRequestId(), exception);
            markFailed(changeLog);
            return false;
        }
    }

    private boolean drainDeduct(SeckillStockChangeLogEntity changeLog) {
        String requestId = changeLog.getRequestId();
        Long bucketShardKey = changeLog.getBucketShardKey();
        if (requestId == null || requestId.isBlank()) {
            markFailed(changeLog);
            return false;
        }

        boolean outboxExists = messageRepository.existsByBusinessKeyAndRoutingKey(
                requestId,
                MessageNames.SECKILL_ORDER_CREATE_ROUTING_KEY,
                bucketShardKey);
        if (outboxExists) {
            return updateStatus(changeLog, SeckillStockChangeLogStatus.OUTBOXING, SeckillStockChangeLogStatus.OUTBOXED);
        }

        SeckillRepository.StockSnapshot snapshot = seckillRepository.findStockSnapshot(requestId, bucketShardKey);
        if (snapshot == null) {
            markFailed(changeLog);
            return false;
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
        return updateStatus(changeLog, SeckillStockChangeLogStatus.OUTBOXING, SeckillStockChangeLogStatus.OUTBOXED);
    }

    private boolean updateStatus(SeckillStockChangeLogEntity changeLog, String expectedStatus, String nextStatus) {
        int updated;
        if (changeLog.getBucketShardKey() != null) {
            updated = changeLogMapper.updateStatusByShard(
                    changeLog.getId(),
                    changeLog.getBucketShardKey(),
                    expectedStatus,
                    nextStatus);
        } else {
            updated = changeLogMapper.updateStatus(changeLog.getId(), expectedStatus, nextStatus);
        }
        return updated > 0;
    }

    private void markFailed(SeckillStockChangeLogEntity changeLog) {
        try {
            updateStatus(changeLog, SeckillStockChangeLogStatus.OUTBOXING, SeckillStockChangeLogStatus.OUTBOX_FAILED);
        } catch (RuntimeException exception) {
            log.warn("Failed to mark seckill change log outbox failed, changeLogId={}, requestId={}",
                    changeLog.getId(), changeLog.getRequestId(), exception);
        }
    }
}

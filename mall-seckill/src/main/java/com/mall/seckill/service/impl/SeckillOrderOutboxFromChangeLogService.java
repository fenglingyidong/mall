package com.mall.seckill.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.util.JsonUtils;
import com.mall.message.ReliableMessagePublisher;
import com.mall.seckill.config.SeckillProperties;
import com.mall.seckill.mapper.SeckillRepository;
import com.mall.seckill.mapper.SeckillStockChangeLogMapper;
import com.mall.seckill.pojo.dto.SeckillOrderRequest;
import com.mall.seckill.pojo.entity.SeckillStockChangeLogEntity;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class SeckillOrderOutboxFromChangeLogService {

    private static final Logger log = LoggerFactory.getLogger(SeckillOrderOutboxFromChangeLogService.class);
    private static final int MIN_BATCH_SIZE = 1;
    private static final int MAX_BATCH_SIZE = 1000;
    private static final String CHANGE_TYPE_DEDUCT = "DEDUCT";

    private final SeckillStockChangeLogMapper changeLogMapper;
    private final SeckillRepository seckillRepository;
    private final ReliableMessagePublisher messagePublisher;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final SeckillProperties properties;
    private final Timer batchDurationTimer;
    private final DistributionSummary batchRecordsSummary;

    @Autowired
    public SeckillOrderOutboxFromChangeLogService(SeckillStockChangeLogMapper changeLogMapper,
                                                  SeckillRepository seckillRepository,
                                                  com.mall.message.ReliableMessageRepository messageRepository,
                                                  ReliableMessagePublisher messagePublisher,
                                                  ObjectMapper objectMapper,
                                                  TransactionTemplate transactionTemplate,
                                                  SeckillProperties properties,
                                                  ObjectProvider<MeterRegistry> meterRegistry) {
        this(changeLogMapper,
                seckillRepository,
                messagePublisher,
                objectMapper,
                transactionTemplate,
                properties,
                meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
    }

    SeckillOrderOutboxFromChangeLogService(SeckillStockChangeLogMapper changeLogMapper,
                                           SeckillRepository seckillRepository,
                                           ReliableMessagePublisher messagePublisher,
                                           ObjectMapper objectMapper,
                                           TransactionTemplate transactionTemplate,
                                           SeckillProperties properties,
                                           MeterRegistry meterRegistry) {
        this.changeLogMapper = changeLogMapper;
        this.seckillRepository = seckillRepository;
        this.messagePublisher = messagePublisher;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        MeterRegistry registry = meterRegistry == null ? new SimpleMeterRegistry() : meterRegistry;
        this.batchDurationTimer = Timer.builder("seckill.outbox.batch.duration")
                .description("Seckill outbox batch drain duration")
                .register(registry);
        this.batchRecordsSummary = DistributionSummary.builder("seckill.outbox.batch.records")
                .description("Seckill outbox batch drained record count")
                .register(registry);
    }

    public int drainShard(Long bucketShardKey, int maxBatches) {
        if (!enabled() || bucketShardKey == null) {
            return 0;
        }
        int total = 0;
        int safeMaxBatches = Math.max(1, maxBatches);
        for (int index = 0; index < safeMaxBatches; index++) {
            int drained = batchDurationTimer.record(() -> drainOneBatch(bucketShardKey, batchSize()));
            if (drained <= 0) {
                break;
            }
            batchRecordsSummary.record(drained);
            total += drained;
            if (drained < batchSize()) {
                break;
            }
        }
        return total;
    }

    public int resetStaleOutboxing(Long bucketShardKey) {
        if (!enabled() || bucketShardKey == null) {
            return 0;
        }
        LocalDateTime before = LocalDateTime.now()
                .minusSeconds(Math.max(1L, properties.getOrderOutbox().getClaimTimeoutSeconds()));
        return changeLogMapper.resetStaleOutboxingByShard(bucketShardKey, before);
    }

    private int drainOneBatch(Long bucketShardKey, int batchSize) {
        List<SeckillStockChangeLogEntity> candidates = changeLogMapper.selectByStatusForConsumeByShard(
                bucketShardKey,
                SeckillStockChangeLogStatus.NEW,
                batchSize);
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }

        List<Long> ids = candidates.stream()
                .map(SeckillStockChangeLogEntity::getId)
                .toList();
        String claimToken = UUID.randomUUID().toString();
        LocalDateTime claimedAt = LocalDateTime.now();
        int claimedCount = changeLogMapper.claimStatusByIdsAndShard(ids, bucketShardKey, claimToken, claimedAt);
        if (claimedCount <= 0) {
            return 0;
        }

        List<SeckillStockChangeLogEntity> claimedLogs = changeLogMapper.selectClaimedByTokenAndShard(bucketShardKey, claimToken);
        if (claimedLogs == null || claimedLogs.isEmpty()) {
            return 0;
        }

        return Boolean.TRUE.equals(transactionTemplate.execute(status ->
                processClaimedBatch(bucketShardKey, claimToken, claimedLogs))) ? claimedLogs.size() : 0;
    }

    private boolean processClaimedBatch(Long bucketShardKey,
                                        String claimToken,
                                        List<SeckillStockChangeLogEntity> claimedLogs) {
        List<SeckillStockChangeLogEntity> deductLogs = claimedLogs.stream()
                .filter(changeLog -> CHANGE_TYPE_DEDUCT.equals(changeLog.getChangeType()))
                .toList();

        List<ReliableMessagePublisher.SeckillOrderCreateOutbox> outboxes = buildOutboxes(bucketShardKey, deductLogs);
        messagePublisher.enqueueSeckillOrderCreateBatch(outboxes);

        List<Long> ids = claimedLogs.stream()
                .map(SeckillStockChangeLogEntity::getId)
                .toList();
        int updated = changeLogMapper.updateStatusByIdsAndClaimToken(
                ids,
                bucketShardKey,
                claimToken,
                SeckillStockChangeLogStatus.OUTBOXED);
        if (updated != ids.size()) {
            throw new IllegalStateException("Seckill outbox claim finalize failed, shard=" + bucketShardKey
                    + ", expected=" + ids.size() + ", actual=" + updated);
        }
        return true;
    }

    private List<ReliableMessagePublisher.SeckillOrderCreateOutbox> buildOutboxes(Long bucketShardKey,
                                                                                   List<SeckillStockChangeLogEntity> deductLogs) {
        if (deductLogs.isEmpty()) {
            return List.of();
        }
        List<String> requestIds = deductLogs.stream()
                .map(SeckillStockChangeLogEntity::getRequestId)
                .filter(requestId -> requestId != null && !requestId.isBlank())
                .distinct()
                .toList();
        Map<String, SeckillRepository.StockSnapshot> snapshots = seckillRepository.findStockSnapshots(requestIds, bucketShardKey);
        Map<Long, Set<Long>> activitySkuIds = deductLogs.stream()
                .collect(Collectors.groupingBy(
                        SeckillStockChangeLogEntity::getActivityId,
                        LinkedHashMap::new,
                        Collectors.mapping(SeckillStockChangeLogEntity::getSkuId, Collectors.toSet())));
        Map<SeckillRepository.ActivitySkuKey, com.mall.seckill.pojo.entity.SeckillSku> skus =
                seckillRepository.findSkusByActivityAndSkuIds(activitySkuIds);

        List<ReliableMessagePublisher.SeckillOrderCreateOutbox> outboxes = new ArrayList<>();
        for (SeckillStockChangeLogEntity changeLog : deductLogs) {
            String requestId = changeLog.getRequestId();
            if (requestId == null || requestId.isBlank()) {
                throw new IllegalStateException("Seckill deduct change log requestId is blank, changeLogId=" + changeLog.getId());
            }
            SeckillRepository.StockSnapshot snapshot = snapshots.get(requestId);
            if (snapshot == null) {
                throw new IllegalStateException("Seckill stock snapshot not found, requestId=" + requestId + ", shard=" + bucketShardKey);
            }
            SeckillRepository.ActivitySkuKey skuKey =
                    new SeckillRepository.ActivitySkuKey(changeLog.getActivityId(), changeLog.getSkuId());
            com.mall.seckill.pojo.entity.SeckillSku sku = skus.get(skuKey);
            if (sku == null) {
                throw new IllegalStateException("Seckill sku not found, activityId=" + changeLog.getActivityId()
                        + ", skuId=" + changeLog.getSkuId());
            }
            SeckillOrderRequest orderRequest = new SeckillOrderRequest(
                    requestId,
                    changeLog.getActivityId(),
                    snapshot.userId(),
                    changeLog.getSkuId(),
                    sku.skuName(),
                    sku.price(),
                    snapshot.quantity(),
                    bucketShardKey);
            outboxes.add(new ReliableMessagePublisher.SeckillOrderCreateOutbox(
                    requestId,
                    JsonUtils.toJson(objectMapper, orderRequest),
                    bucketShardKey));
        }
        return outboxes;
    }

    private boolean enabled() {
        return properties.getOrderOutbox().isEnabled();
    }

    private int batchSize() {
        return Math.min(MAX_BATCH_SIZE, Math.max(MIN_BATCH_SIZE, properties.getOrderOutbox().getBatchSize()));
    }
}

package com.mall.seckill.service.impl;

import com.mall.common.exception.BusinessException;
import com.mall.seckill.config.SeckillProperties;
import com.mall.seckill.mapper.SeckillBucketConfigMapper;
import com.mall.seckill.mapper.SeckillStockBucketMapper;
import com.mall.seckill.mapper.SeckillStockChangeLogMapper;
import com.mall.seckill.mapper.SeckillStockNotEnoughException;
import com.mall.seckill.pojo.entity.SeckillBucketConfigEntity;
import com.mall.seckill.pojo.entity.SeckillStockBucketEntity;
import com.mall.seckill.pojo.entity.SeckillStockChangeLogEntity;
import com.mall.seckill.pojo.entity.SeckillStockSnapshotEntity;
import com.mall.seckill.pojo.vo.StockVersion;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

@Component
public class SeckillBucketService {

    static final String BUCKET_TYPE_BUCKET = "BUCKET";
    static final String STATUS_ACTIVE = "ACTIVE";
    static final String STATUS_EMPTY = "EMPTY";
    static final String CHANGE_DEDUCT = "DEDUCT";
    static final String CHANGE_RELEASE = "RELEASE";
    static final String CHANGE_STATUS_NEW = "NEW";
    // DEDUCT submit hot path skips the post-update bucket read; ledger consumers use quantityDelta.
    static final int AFTER_QUANTITY_UNKNOWN = -1;

    private final SeckillBucketConfigMapper configMapper;
    private final SeckillStockBucketMapper bucketMapper;
    private final SeckillStockChangeLogMapper changeLogMapper;
    private final SeckillBucketTransferService transferService;
    private final SeckillProperties properties;
    private final SeckillBucketShardRouter shardRouter;
    private final LongSupplier currentTimeMillis;
    private final MeterRegistry meterRegistry;
    private final SeckillBucketAvailabilityCoordinator availabilityCoordinator;
    private final Timer routeTimer;
    private final Timer dbDeductTimer;
    private final Timer changeLogInsertTimer;
    private final Timer stockVersionTimer;
    private final ConcurrentHashMap<String, AtomicLong> requestTransferLastAttemptMillis = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> exhaustedBucketSkipUntilMillis = new ConcurrentHashMap<>();

    public SeckillBucketService(SeckillBucketConfigMapper configMapper,
                                SeckillStockBucketMapper bucketMapper,
                                SeckillStockChangeLogMapper changeLogMapper) {
        this(configMapper, bucketMapper, changeLogMapper, (SeckillBucketTransferService) null);
    }

    @Autowired
    public SeckillBucketService(SeckillBucketConfigMapper configMapper,
                                SeckillStockBucketMapper bucketMapper,
                                SeckillStockChangeLogMapper changeLogMapper,
                                ObjectProvider<SeckillBucketTransferService> transferService,
                                ObjectProvider<SeckillBucketShardRouter> shardRouter,
                                ObjectProvider<SeckillBucketAvailabilityCoordinator> availabilityCoordinator,
                                SeckillProperties properties,
                                MeterRegistry meterRegistry) {
        this(configMapper,
                bucketMapper,
                changeLogMapper,
                transferService.getIfAvailable(),
                properties,
                meterRegistry,
                System::currentTimeMillis,
                shardRouter.getIfAvailable(),
                availabilityCoordinator.getIfAvailable());
    }

    SeckillBucketService(SeckillBucketConfigMapper configMapper,
                         SeckillStockBucketMapper bucketMapper,
                         SeckillStockChangeLogMapper changeLogMapper,
                         SeckillBucketTransferService transferService) {
        this(configMapper, bucketMapper, changeLogMapper, transferService, new SeckillProperties());
    }

    SeckillBucketService(SeckillBucketConfigMapper configMapper,
                         SeckillStockBucketMapper bucketMapper,
                         SeckillStockChangeLogMapper changeLogMapper,
                         SeckillBucketTransferService transferService,
                         SeckillProperties properties) {
        this(configMapper, bucketMapper, changeLogMapper, transferService, properties, new SimpleMeterRegistry());
    }

    SeckillBucketService(SeckillBucketConfigMapper configMapper,
                         SeckillStockBucketMapper bucketMapper,
                         SeckillStockChangeLogMapper changeLogMapper,
                         SeckillBucketTransferService transferService,
                         SeckillProperties properties,
                         SeckillBucketAvailabilityCoordinator availabilityCoordinator) {
        this(configMapper, bucketMapper, changeLogMapper, transferService, properties,
                new SimpleMeterRegistry(), System::currentTimeMillis, null, availabilityCoordinator);
    }

    SeckillBucketService(SeckillBucketConfigMapper configMapper,
                         SeckillStockBucketMapper bucketMapper,
                         SeckillStockChangeLogMapper changeLogMapper,
                         SeckillBucketTransferService transferService,
                         SeckillProperties properties,
                         MeterRegistry meterRegistry) {
        this(configMapper, bucketMapper, changeLogMapper, transferService, properties, meterRegistry, System::currentTimeMillis);
    }

    SeckillBucketService(SeckillBucketConfigMapper configMapper,
                         SeckillStockBucketMapper bucketMapper,
                         SeckillStockChangeLogMapper changeLogMapper,
                         SeckillBucketTransferService transferService,
                         SeckillProperties properties,
                         LongSupplier currentTimeMillis) {
        this(configMapper, bucketMapper, changeLogMapper, transferService, properties, new SimpleMeterRegistry(), currentTimeMillis);
    }

    SeckillBucketService(SeckillBucketConfigMapper configMapper,
                         SeckillStockBucketMapper bucketMapper,
                         SeckillStockChangeLogMapper changeLogMapper,
                         SeckillBucketTransferService transferService,
                         SeckillProperties properties,
                         MeterRegistry meterRegistry,
                         LongSupplier currentTimeMillis) {
        this(configMapper, bucketMapper, changeLogMapper, transferService, properties, meterRegistry, currentTimeMillis, null);
    }

    SeckillBucketService(SeckillBucketConfigMapper configMapper,
                         SeckillStockBucketMapper bucketMapper,
                         SeckillStockChangeLogMapper changeLogMapper,
                         SeckillBucketTransferService transferService,
                         SeckillProperties properties,
                         MeterRegistry meterRegistry,
                         LongSupplier currentTimeMillis,
                         SeckillBucketShardRouter shardRouter) {
        this(configMapper, bucketMapper, changeLogMapper, transferService, properties, meterRegistry, currentTimeMillis,
                shardRouter, null);
    }

    SeckillBucketService(SeckillBucketConfigMapper configMapper,
                         SeckillStockBucketMapper bucketMapper,
                         SeckillStockChangeLogMapper changeLogMapper,
                         SeckillBucketTransferService transferService,
                         SeckillProperties properties,
                         MeterRegistry meterRegistry,
                         LongSupplier currentTimeMillis,
                         SeckillBucketShardRouter shardRouter,
                         SeckillBucketAvailabilityCoordinator availabilityCoordinator) {
        SeckillProperties effectiveProperties = properties == null ? new SeckillProperties() : properties;
        this.configMapper = configMapper;
        this.bucketMapper = bucketMapper;
        this.changeLogMapper = changeLogMapper;
        this.transferService = transferService;
        this.properties = effectiveProperties;
        this.shardRouter = shardRouter == null ? new SeckillBucketShardRouter(effectiveProperties) : shardRouter;
        this.currentTimeMillis = currentTimeMillis == null ? System::currentTimeMillis : currentTimeMillis;
        this.meterRegistry = meterRegistry == null ? new SimpleMeterRegistry() : meterRegistry;
        this.availabilityCoordinator = availabilityCoordinator;
        this.routeTimer = timer("seckill.submit.record.bucket.route", "Official submit bucket route and select latency");
        this.dbDeductTimer = timer("seckill.submit.record.bucket.db.deduct", "Official submit bucket conditional deduct SQL latency");
        this.changeLogInsertTimer = timer("seckill.submit.record.bucket.change-log.insert", "Official submit bucket stock change log insert latency");
        this.stockVersionTimer = timer("seckill.submit.record.bucket.stock-version", "Official submit bucket stock version read latency");
    }

    public SelectedBucket selectBucket(Long activityId, Long skuId) {
        return selectBucketWithTimer(activityId, skuId, Set.of());
    }

    public BucketDeductOnlyResult deductOnly(Long activityId, Long skuId, int quantity) {
        SelectedBucket selectedBucket = selectBucket(activityId, skuId);
        int updated = dbDeductTimer.record(() ->
                bucketMapper.deductSaleableAndIncreaseVersionByShard(
                        selectedBucket.bucketId(),
                        selectedBucket.bucketShardKey(),
                        quantity));
        return new BucketDeductOnlyResult(updated > 0, selectedBucket);
    }

    private SelectedBucket selectBucketWithTimer(Long activityId, Long skuId, Set<Long> excludedBucketIds) {
        return routeTimer.record(() -> selectBucketExcluding(activityId, skuId, excludedBucketIds));
    }

    private SelectedBucket selectBucketExcluding(Long activityId, Long skuId, Set<Long> excludedBucketIds) {
        SeckillBucketConfigEntity config = requireConfig(activityId, skuId);
        List<Integer> survivors = parseSurvivors(config.getSurvivorBuckets());
        if (survivors.isEmpty()) {
            throw new SeckillStockNotEnoughException();
        }
        int start = ThreadLocalRandom.current().nextInt(survivors.size());
        for (int i = 0; i < survivors.size(); i++) {
            Integer bucketNo = survivors.get((start + i) % survivors.size());
            if (shouldSkipTemporarily(activityId, skuId, bucketNo)) {
                continue;
            }
            Long shardKey = shardRouter.bucketShardKey(bucketNo);
            SeckillStockBucketEntity bucket = bucketMapper.selectActiveBucketByShard(activityId, skuId, bucketNo, shardKey);
            if (bucket != null && !excludedBucketIds.contains(bucket.getId())) {
                Long bucketShardKey = bucket.getShardKey() == null ? shardKey : bucket.getShardKey();
                recordShardHit(bucketShardKey);
                return new SelectedBucket(
                        bucket.getId(),
                        bucket.getBucketNo(),
                        bucketShardKey,
                        config.getStrategyVersion(),
                        config.getId());
            }
        }
        throw new SeckillStockNotEnoughException();
    }

    public BucketMutationResult deduct(SelectedBucket selectedBucket,
                                       String requestId,
                                       Long activityId,
                                       Long skuId,
                                       int quantity) {
        SelectedBucket currentBucket = selectedBucket;
        Set<Long> exhaustedBucketIds = new LinkedHashSet<>();
        Set<Long> transferTargetBucketIds = new LinkedHashSet<>();
        int transferAttempts = 0;
        while (true) {
            SelectedBucket bucketToDeduct = currentBucket;
            int updated = dbDeductTimer.record(() ->
                    bucketMapper.deductSaleableAndIncreaseVersionByShard(
                            bucketToDeduct.bucketId(),
                            bucketToDeduct.bucketShardKey(),
                            quantity));
            if (updated > 0) {
                return afterDeducted(bucketToDeduct, requestId, activityId, skuId, quantity);
            }
            exhaustedBucketIds.add(bucketToDeduct.bucketId());
            SeckillStockBucketEntity exhaustedBucket = markBucketEmptyIfExhausted(activityId, skuId,
                    bucketToDeduct.bucketId(), bucketToDeduct.bucketShardKey());
            if (transferAttempts < maxTransferAttempts()
                    && transferTargetBucketIds.add(bucketToDeduct.bucketId())
                    && shouldAttemptRequestTransfer(activityId, skuId, bucketToDeduct.bucketId())) {
                transferAttempts++;
                if (tryTransfer(requestId, activityId, skuId, exhaustedBucket)) {
                    continue;
                }
            }
            currentBucket = selectBucketWithTimer(activityId, skuId, exhaustedBucketIds);
        }
    }

    public BucketMutationResult deductSelected(SelectedBucket selectedBucket,
                                               String requestId,
                                               Long activityId,
                                               Long skuId,
                                               int quantity) {
        if (deductSelectedBucket(selectedBucket, quantity)) {
            return afterDeducted(selectedBucket, requestId, activityId, skuId, quantity);
        }
        SeckillStockBucketEntity exhaustedBucket = markBucketEmptyIfExhausted(activityId, skuId,
                selectedBucket.bucketId(), selectedBucket.bucketShardKey());
        if (maxTransferAttempts() > 0
                && shouldAttemptRequestTransfer(activityId, skuId, selectedBucket.bucketId())
                && tryTransfer(requestId, activityId, skuId, exhaustedBucket)
                && deductSelectedBucket(selectedBucket, quantity)) {
            return afterDeducted(selectedBucket, requestId, activityId, skuId, quantity);
        }
        markBucketEmptyIfExhausted(activityId, skuId, selectedBucket.bucketId(), selectedBucket.bucketShardKey());
        throw new SeckillStockNotEnoughException();
    }

    private boolean deductSelectedBucket(SelectedBucket selectedBucket, int quantity) {
        return dbDeductTimer.record(() ->
                bucketMapper.deductSaleableAndIncreaseVersionByShard(
                        selectedBucket.bucketId(),
                        selectedBucket.bucketShardKey(),
                        quantity)) > 0;
    }

    private BucketMutationResult afterDeducted(SelectedBucket selectedBucket,
                                               String requestId,
                                               Long activityId,
                                               Long skuId,
                                               int quantity) {
        SeckillStockChangeLogEntity changeLog = changeLog(
                requestId,
                activityId,
                skuId,
                selectedBucket.bucketId(),
                selectedBucket.bucketNo(),
                selectedBucket.bucketShardKey(),
                CHANGE_DEDUCT,
                -quantity,
                AFTER_QUANTITY_UNKNOWN);
        changeLogInsertTimer.record(() -> changeLogMapper.insert(changeLog));
        return new BucketMutationResult(hotPathStockVersion(activityId, skuId), changeLog.getId(), selectedBucket);
    }

    private SeckillStockBucketEntity markBucketEmptyIfExhausted(Long activityId,
                                                                Long skuId,
                                                                Long bucketId,
                                                                Long bucketShardKey) {
        SeckillStockBucketEntity bucket = bucketMapper.selectByIdAndShardKey(bucketId, bucketShardKey);
        if (bucket != null && bucket.getSaleableQuantity() != null && bucket.getSaleableQuantity() <= 0) {
            rememberTemporarilyExhausted(activityId, skuId, bucket.getBucketNo());
            offlineIfEmpty(activityId, skuId, bucket, bucketShardKey);
        }
        return bucket;
    }

    private boolean shouldSkipTemporarily(Long activityId, Long skuId, Integer bucketNo) {
        if (bucketNo == null) {
            return false;
        }
        long ttlMillis = exhaustedBucketTtlMillis();
        if (ttlMillis <= 0) {
            return false;
        }
        String key = exhaustedBucketKey(activityId, skuId, bucketNo);
        Long skipUntilMillis = exhaustedBucketSkipUntilMillis.get(key);
        if (skipUntilMillis == null) {
            return false;
        }
        long now = currentTimeMillis.getAsLong();
        if (now < skipUntilMillis) {
            return true;
        }
        exhaustedBucketSkipUntilMillis.remove(key, skipUntilMillis);
        return false;
    }

    private void rememberTemporarilyExhausted(Long activityId, Long skuId, Integer bucketNo) {
        if (bucketNo == null) {
            return;
        }
        long ttlMillis = exhaustedBucketTtlMillis();
        if (ttlMillis <= 0) {
            return;
        }
        exhaustedBucketSkipUntilMillis.put(
                exhaustedBucketKey(activityId, skuId, bucketNo),
                currentTimeMillis.getAsLong() + ttlMillis);
    }

    private String exhaustedBucketKey(Long activityId, Long skuId, Integer bucketNo) {
        return activityId + ":" + skuId + ":" + bucketNo;
    }

    private long exhaustedBucketTtlMillis() {
        return Math.max(0, properties.getBucket().getAvailability().getExhaustedBucketTtlMillis());
    }

    private boolean tryTransfer(String requestId,
                                Long activityId,
                                Long skuId,
                                SeckillStockBucketEntity targetBucket) {
        if (transferService == null) {
            return false;
        }
        return transferService.transfer(requestId, activityId, skuId, targetBucket).transferred();
    }

    private int maxTransferAttempts() {
        return transferService == null ? 0 : transferService.maxAttempts();
    }

    private boolean shouldAttemptRequestTransfer(Long activityId, Long skuId, Long bucketId) {
        long minIntervalMillis = requestTransferMinIntervalMillis();
        if (minIntervalMillis <= 0) {
            return true;
        }
        String key = activityId + ":" + skuId + ":" + bucketId;
        AtomicLong lastAttemptMillis = requestTransferLastAttemptMillis.computeIfAbsent(key, ignored -> new AtomicLong(0));
        long now = currentTimeMillis.getAsLong();
        while (true) {
            long previous = lastAttemptMillis.get();
            if (previous > 0 && now - previous < minIntervalMillis) {
                return false;
            }
            if (lastAttemptMillis.compareAndSet(previous, now)) {
                return true;
            }
        }
    }

    private long requestTransferMinIntervalMillis() {
        return Math.max(0, properties.getBucket().getTransfer().getRequestFallbackMinIntervalMillis());
    }

    public StockVersion release(SeckillStockSnapshotEntity snapshot) {
        if (snapshot.getBucketId() == null) {
            throw new BusinessException(409, "Seckill bucket snapshot missing bucket id");
        }
        Long bucketShardKey = snapshotBucketShardKey(snapshot);
        int updated = bucketMapper.releaseSaleableAndIncreaseVersionByShard(
                snapshot.getBucketId(),
                bucketShardKey,
                snapshot.getQuantity());
        if (updated == 0) {
            throw new BusinessException(409, "Seckill bucket release failed");
        }
        SeckillStockBucketEntity bucket = requireBucket(snapshot.getBucketId(), bucketShardKey);
        changeLogMapper.insert(changeLog(
                snapshot.getRequestId(),
                snapshot.getActivityId(),
                snapshot.getSkuId(),
                bucket,
                CHANGE_RELEASE,
                snapshot.getQuantity()));
        if (bucket.getSaleableQuantity() != null && bucket.getSaleableQuantity() > 0) {
            onlineIfAvailable(snapshot.getActivityId(), snapshot.getSkuId(), bucket);
        }
        return hotPathStockVersion(snapshot.getActivityId(), snapshot.getSkuId());
    }

    private SeckillBucketConfigEntity requireConfig(Long activityId, Long skuId) {
        SeckillBucketConfigEntity config = configMapper.selectEnabled(activityId, skuId);
        if (config == null) {
            throw new BusinessException(409, "Seckill bucket config not found");
        }
        return config;
    }

    private SeckillStockBucketEntity requireBucket(Long bucketId, Long bucketShardKey) {
        SeckillStockBucketEntity bucket = bucketMapper.selectByIdAndShardKey(bucketId, bucketShardKey);
        if (bucket == null) {
            throw new BusinessException(409, "Seckill bucket not found");
        }
        return bucket;
    }

    private Long snapshotBucketShardKey(SeckillStockSnapshotEntity snapshot) {
        if (snapshot.getBucketShardKey() != null) {
            return snapshot.getBucketShardKey();
        }
        if (snapshot.getBucketNo() != null) {
            return shardRouter.bucketShardKey(snapshot.getBucketNo());
        }
        throw new BusinessException(409, "Seckill bucket snapshot missing bucket shard key");
    }

    private void offlineIfEmpty(Long activityId, Long skuId, SeckillStockBucketEntity bucket, Long bucketShardKey) {
        int updated = bucketMapper.markEmptyIfNoSaleableByShard(bucket.getId(), bucketShardKey);
        if (updated > 0 && availabilityCoordinator != null) {
            availabilityCoordinator.signalPossiblyEmpty(
                    activityId,
                    skuId,
                    bucket.getId(),
                    bucket.getBucketNo(),
                    bucketShardKey);
        }
    }

    private void onlineIfAvailable(Long activityId, Long skuId, SeckillStockBucketEntity bucket) {
        bucketMapper.updateStatus(bucket.getId(), bucket.getShardKey(), STATUS_ACTIVE);
        if (availabilityCoordinator != null) {
            availabilityCoordinator.signalPossiblyAvailable(
                    activityId,
                    skuId,
                    bucket.getId(),
                    bucket.getBucketNo(),
                    bucket.getShardKey());
        }
    }

    public StockVersion aggregateStockVersion(Long activityId, Long skuId) {
        StockVersion stockVersion = bucketMapper.selectAggregateStockVersion(activityId, skuId);
        return stockVersion == null ? new StockVersion(0, 0L) : stockVersion;
    }

    private StockVersion hotPathStockVersion(Long activityId, Long skuId) {
        if (!properties.getBucket().isHotPathAggregateRead()) {
            return null;
        }
        return stockVersionTimer.record(() -> aggregateStockVersion(activityId, skuId));
    }

    private void recordShardHit(Long bucketShardKey) {
        meterRegistry.counter("seckill.bucket.shard.hit", "shard", shardRouter.physicalShardTag(bucketShardKey)).increment();
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
        return changeLog(
                requestId,
                activityId,
                skuId,
                bucket.getId(),
                bucket.getBucketNo(),
                bucket.getShardKey(),
                changeType,
                quantityDelta,
                bucket.getSaleableQuantity());
    }

    private SeckillStockChangeLogEntity changeLog(String requestId,
                                                  Long activityId,
                                                  Long skuId,
                                                  Long bucketId,
                                                  Integer bucketNo,
                                                  Long bucketShardKey,
                                                  String changeType,
                                                  int quantityDelta,
                                                  Integer afterQuantity) {
        LocalDateTime now = LocalDateTime.now();
        SeckillStockChangeLogEntity changeLog = new SeckillStockChangeLogEntity();
        changeLog.setRequestId(requestId);
        changeLog.setActivityId(activityId);
        changeLog.setSkuId(skuId);
        changeLog.setBucketId(bucketId);
        changeLog.setBucketNo(bucketNo);
        changeLog.setBucketShardKey(bucketShardKey);
        changeLog.setChangeType(changeType);
        changeLog.setQuantityDelta(quantityDelta);
        changeLog.setAfterQuantity(afterQuantity);
        changeLog.setStatus(CHANGE_STATUS_NEW);
        changeLog.setCreatedAt(now);
        changeLog.setUpdatedAt(now);
        return changeLog;
    }

    private List<Integer> parseSurvivors(String rawSurvivors) {
        if (rawSurvivors == null || rawSurvivors.isBlank()) {
            return new ArrayList<>();
        }
        List<Integer> survivors = new ArrayList<>();
        for (String part : rawSurvivors.split(",")) {
            if (part == null || part.isBlank()) {
                continue;
            }
            survivors.add(Integer.valueOf(part.trim()));
        }
        return survivors;
    }

    private String formatSurvivors(List<Integer> survivors) {
        return survivors.stream()
                .sorted(Comparator.naturalOrder())
                .map(String::valueOf)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    public record SelectedBucket(Long bucketId,
                                 Integer bucketNo,
                                 Long bucketShardKey,
                                 Long strategyVersion,
                                 Long configId) {
        public SelectedBucket(Long bucketId, Integer bucketNo, Long strategyVersion, Long configId) {
            this(bucketId, bucketNo, bucketNo == null ? null : bucketNo.longValue(), strategyVersion, configId);
        }
    }

    public record BucketMutationResult(StockVersion stockVersion, Long changeId, SelectedBucket selectedBucket) {
        public BucketMutationResult(StockVersion stockVersion, Long changeId) {
            this(stockVersion, changeId, null);
        }
    }

    public record BucketDeductOnlyResult(boolean deducted, SelectedBucket selectedBucket) {
    }
}

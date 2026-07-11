package com.mall.seckill.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.common.exception.BusinessException;
import com.mall.seckill.config.SeckillProperties;
import com.mall.seckill.pojo.entity.SeckillActivity;
import com.mall.seckill.pojo.entity.SeckillActivityEntity;
import com.mall.seckill.pojo.entity.SeckillResultEntity;
import com.mall.seckill.pojo.entity.SeckillStockSnapshotEntity;
import com.mall.seckill.pojo.entity.SeckillSku;
import com.mall.seckill.pojo.entity.SeckillSkuEntity;
import com.mall.seckill.pojo.vo.SeckillActivityView;
import com.mall.seckill.pojo.vo.SeckillResult;
import com.mall.seckill.pojo.vo.StockDeductProbeResponse;
import com.mall.seckill.pojo.vo.StockReleaseResult;
import com.mall.seckill.pojo.vo.StockVersion;
import com.mall.seckill.service.impl.SeckillBucketService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
public class SeckillRepository {

    private static final ZoneId ZONE_ID = ZoneId.systemDefault();
    private static final int MESSAGE_MAX_LENGTH = 255;

    private final SeckillActivityMapper activityMapper;
    private final SeckillSkuMapper skuMapper;
    private final SeckillResultMapper resultMapper;
    private final SeckillStockSnapshotMapper snapshotMapper;
    private final SeckillStockChangeLogMapper changeLogMapper;
    private final SeckillBucketService bucketService;
    private final SeckillProperties properties;
    private final MeterRegistry meterRegistry;
    private final Timer stockDeductTotalTimer;
    private final Timer stockDeductUpdateTimer;
    private final Timer stockDeductSelectTimer;
    private final Timer stockDeductUpdateOnlyTotalTimer;
    private final Timer stockDeductUpdateOnlyUpdateTimer;
    private final Timer resultSaveTimer;
    private final Timer releaseDeductionTotalTimer;
    private final Timer releaseDeductionSnapshotUpdateTimer;
    private final Timer releaseDeductionStockUpdateTimer;
    private final Timer releaseDeductionStockSelectTimer;

    public SeckillRepository(SeckillActivityMapper activityMapper,
                              SeckillSkuMapper skuMapper,
                              SeckillResultMapper resultMapper,
                              SeckillStockSnapshotMapper snapshotMapper,
                              MeterRegistry meterRegistry) {
        this(activityMapper, skuMapper, resultMapper, snapshotMapper, null, (SeckillBucketService) null, new SeckillProperties(), meterRegistry);
    }

    @Autowired
    public SeckillRepository(SeckillActivityMapper activityMapper,
                             SeckillSkuMapper skuMapper,
                             SeckillResultMapper resultMapper,
                             SeckillStockSnapshotMapper snapshotMapper,
                             SeckillStockChangeLogMapper changeLogMapper,
                             ObjectProvider<SeckillBucketService> bucketService,
                             SeckillProperties properties,
                             MeterRegistry meterRegistry) {
        this(activityMapper,
                skuMapper,
                resultMapper,
                snapshotMapper,
                changeLogMapper,
                bucketService.getIfAvailable(),
                properties,
                meterRegistry);
    }

    SeckillRepository(SeckillActivityMapper activityMapper,
                      SeckillSkuMapper skuMapper,
                      SeckillResultMapper resultMapper,
                      SeckillStockSnapshotMapper snapshotMapper,
                      SeckillBucketService bucketService,
                      SeckillProperties properties,
                      MeterRegistry meterRegistry) {
        this(activityMapper, skuMapper, resultMapper, snapshotMapper, null, bucketService, properties, meterRegistry);
    }

    SeckillRepository(SeckillActivityMapper activityMapper,
                      SeckillSkuMapper skuMapper,
                      SeckillResultMapper resultMapper,
                      SeckillStockSnapshotMapper snapshotMapper,
                      SeckillStockChangeLogMapper changeLogMapper,
                      SeckillBucketService bucketService,
                      SeckillProperties properties,
                      MeterRegistry meterRegistry) {
        this.activityMapper = activityMapper;
        this.skuMapper = skuMapper;
        this.resultMapper = resultMapper;
        this.snapshotMapper = snapshotMapper;
        this.changeLogMapper = changeLogMapper;
        this.bucketService = bucketService;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.stockDeductTotalTimer = timer("seckill.stock.deduct.total", "Stock-only load test total latency");
        this.stockDeductUpdateTimer = timer("seckill.stock.deduct.update", "Stock-only load test update latency");
        this.stockDeductSelectTimer = timer("seckill.stock.deduct.select", "Stock-only load test stock/version select latency");
        this.stockDeductUpdateOnlyTotalTimer = timer("seckill.stock.deduct.update-only.total", "Stock-only load test MyBatis update-only total latency");
        this.stockDeductUpdateOnlyUpdateTimer = timer("seckill.stock.deduct.update-only.update", "Stock-only load test MyBatis update-only update latency");
        this.resultSaveTimer = timer("seckill.submit.result.save", "Official submit result save latency");
        this.releaseDeductionTotalTimer = timer("seckill.submit.release.total", "Official submit stock release transaction latency");
        this.releaseDeductionSnapshotUpdateTimer = timer("seckill.submit.release.snapshot.update", "Official submit stock snapshot release update latency");
        this.releaseDeductionStockUpdateTimer = timer("seckill.submit.release.stock.update", "Official submit stock release update latency");
        this.releaseDeductionStockSelectTimer = timer("seckill.submit.release.stock.select", "Official submit stock release version select latency");
    }

    public List<SeckillActivityView> activityViews() {
        return activityMapper.selectList(Wrappers.emptyWrapper()).stream()
                .map(activity -> new SeckillActivityView(
                        activity.getId(),
                        activity.getName(),
                        toInstant(activity.getStartAt()),
                        toInstant(activity.getEndAt()),
                        skuMapper.selectList(Wrappers.<SeckillSkuEntity>lambdaQuery()
                                        .eq(SeckillSkuEntity::getActivityId, activity.getId()))
                                .stream()
                                .map(this::toDomain)
                                .toList()
                ))
                .toList();
    }

    public SeckillActivity requireActivity(Long activityId) {
        SeckillActivityEntity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BusinessException(404, "Seckill activity not found");
        }
        return new SeckillActivity(activity.getId(), activity.getName(), toInstant(activity.getStartAt()), toInstant(activity.getEndAt()));
    }

    public SeckillSku requireSku(Long activityId, Long skuId) {
        SeckillSkuEntity sku = skuMapper.selectOne(Wrappers.<SeckillSkuEntity>lambdaQuery()
                .eq(SeckillSkuEntity::getActivityId, activityId)
                .eq(SeckillSkuEntity::getSkuId, skuId));
        if (sku == null) {
            throw new BusinessException(404, "Seckill SKU not found");
        }
        return toDomain(sku);
    }

    public SeckillBucketService.SelectedBucket selectBucket(Long activityId, Long skuId) {
        if (!bucketModeEnabled()) {
            throw new BusinessException(409, "Seckill bucket mode is not enabled");
        }
        return bucketService.selectBucket(activityId, skuId);
    }

    public void markRegisteredSnapshotFailed(String requestId, String message) {
        snapshotMapper.releaseActiveKeyIfRegistered(requestId, truncate(message, MESSAGE_MAX_LENGTH));
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean failRegisteredSnapshotIfPresent(String requestId, Long bucketShardKey, String message) {
        if (requestId == null || requestId.isBlank() || bucketShardKey == null) {
            return false;
        }
        String failureMessage = truncate(message, MESSAGE_MAX_LENGTH);
        int updated = snapshotMapper.releaseActiveKeyIfRegisteredByShard(requestId, bucketShardKey, failureMessage);
        if (updated <= 0) {
            return false;
        }
        saveResult(new SeckillResult(requestId, "FAILED", null, failureMessage));
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public StockDeductProbeResponse deductStockOnly(Long stockId, int quantity) {
        Timer.Sample totalSample = Timer.start(meterRegistry);
        try {
            boolean deducted = stockDeductUpdateTimer.record(() ->
                    skuMapper.deductStockAndIncreaseVersionById(stockId, quantity) > 0);
            StockVersion stockVersion = stockDeductSelectTimer.record(() ->
                    skuMapper.selectStockVersionById(stockId));
            return new StockDeductProbeResponse(deducted, stockVersion.stock(), stockVersion.version());
        } finally {
            totalSample.stop(stockDeductTotalTimer);
        }
    }

    public StockDeductProbeResponse deductStockUpdateOnly(Long stockId, int quantity) {
        Timer.Sample totalSample = Timer.start(meterRegistry);
        try {
            boolean deducted = stockDeductUpdateOnlyUpdateTimer.record(() ->
                    skuMapper.deductStockAndIncreaseVersionById(stockId, quantity) > 0);
            return new StockDeductProbeResponse(deducted, null, null);
        } finally {
            totalSample.stop(stockDeductUpdateOnlyTotalTimer);
        }
    }

    public StockVersion stockVersion(Long stockId) {
        return skuMapper.selectStockVersionById(stockId);
    }

    public StockVersion stockVersion(Long stockId, Long activityId, Long skuId) {
        if (bucketModeEnabled()) {
            return bucketService.aggregateStockVersion(activityId, skuId);
        }
        return stockVersion(stockId);
    }

    @Transactional(rollbackFor = Exception.class)
    public StockSnapshot confirmDeduction(String requestId, String orderSn, String message) {
        return confirmDeduction(requestId, null, orderSn, message);
    }

    @Transactional(rollbackFor = Exception.class)
    public StockSnapshot confirmDeduction(String requestId, Long bucketShardKey, String orderSn, String message) {
        SeckillStockSnapshotEntity snapshot = selectSnapshot(requestId, bucketShardKey);
        if (snapshot == null) {
            return null;
        }
        String currentStatus = snapshot.getStatus();
        boolean confirmable = "DEDUCTED".equals(currentStatus)
                || ("REGISTERED".equals(currentStatus)
                && hasDeductChangeLog(requestId, snapshot.getBucketShardKey()));
        if (confirmable) {
            LocalDateTime now = LocalDateTime.now();
            String resultMessage = messageOrDefault(message, "Order created");
            int updated = snapshotMapper.update(null, Wrappers.<SeckillStockSnapshotEntity>lambdaUpdate()
                    .eq(SeckillStockSnapshotEntity::getRequestId, requestId)
                    .eq(bucketShardKey != null, SeckillStockSnapshotEntity::getBucketShardKey, bucketShardKey)
                    .eq(SeckillStockSnapshotEntity::getStatus, currentStatus)
                    .set(SeckillStockSnapshotEntity::getStatus, "CONFIRMED")
                    .set(SeckillStockSnapshotEntity::getOrderSn, orderSn)
                    .set(SeckillStockSnapshotEntity::getMessage, resultMessage)
                    .set(SeckillStockSnapshotEntity::getUpdatedAt, now));
            if (updated > 0) {
                snapshot.setStatus("CONFIRMED");
                snapshot.setOrderSn(orderSn);
                snapshot.setMessage(resultMessage);
                snapshot.setUpdatedAt(now);
            }
        }
        return toStockSnapshot(snapshot);
    }

    @Transactional(rollbackFor = Exception.class)
    public StockReleaseResult releaseDeduction(String requestId, String message) {
        return releaseDeduction(requestId, null, message);
    }

    @Transactional(rollbackFor = Exception.class)
    public StockReleaseResult releaseDeduction(String requestId, Long bucketShardKey, String message) {
        return releaseSnapshot(requestId, bucketShardKey, message, "DEDUCTED");
    }

    @Transactional(rollbackFor = Exception.class)
    public StockReleaseResult releaseConfirmedDeduction(String requestId, Long bucketShardKey, String message) {
        return releaseSnapshot(requestId, bucketShardKey, message, "CONFIRMED");
    }

    private StockReleaseResult releaseSnapshot(String requestId, Long bucketShardKey, String message, String releasableStatus) {
        Timer.Sample totalSample = Timer.start(meterRegistry);
        try {
            SeckillStockSnapshotEntity snapshot = selectSnapshot(requestId, bucketShardKey);
            if (snapshot == null) {
                return null;
            }
            StockVersion stockVersion = null;
            String currentStatus = snapshot.getStatus();
            boolean releasable = releasableStatus.equals(currentStatus)
                    || ("DEDUCTED".equals(releasableStatus)
                    && "REGISTERED".equals(currentStatus)
                    && hasDeductChangeLog(requestId, snapshot.getBucketShardKey()));
            if (releasable) {
                LocalDateTime now = LocalDateTime.now();
                String resultMessage = messageOrDefault(message, "Stock released");
                int updated = releaseDeductionSnapshotUpdateTimer.record(() -> snapshotMapper.update(null, Wrappers.<SeckillStockSnapshotEntity>lambdaUpdate()
                        .eq(SeckillStockSnapshotEntity::getRequestId, requestId)
                        .eq(bucketShardKey != null, SeckillStockSnapshotEntity::getBucketShardKey, bucketShardKey)
                        .eq(SeckillStockSnapshotEntity::getStatus, currentStatus)
                        .set(SeckillStockSnapshotEntity::getStatus, "RELEASING")
                        .set(SeckillStockSnapshotEntity::getMessage, resultMessage)
                        .set(SeckillStockSnapshotEntity::getUpdatedAt, now)));
                if (updated > 0) {
                    if (bucketModeEnabled() && snapshot.getBucketId() != null) {
                        stockVersion = releaseDeductionStockUpdateTimer.record(() -> bucketService.release(snapshot));
                    } else {
                        Long stockId = resolveSnapshotStockId(snapshot);
                        if (releaseDeductionStockUpdateTimer.record(() -> skuMapper.releaseStockAndIncreaseVersionById(stockId, snapshot.getQuantity())) == 0) {
                            throw new BusinessException(409, "Seckill stock ledger release failed");
                        }
                        stockVersion = releaseDeductionStockSelectTimer.record(() -> skuMapper.selectStockVersionById(stockId));
                    }
                    int released = snapshotMapper.update(null, Wrappers.<SeckillStockSnapshotEntity>lambdaUpdate()
                            .eq(SeckillStockSnapshotEntity::getRequestId, requestId)
                            .eq(bucketShardKey != null, SeckillStockSnapshotEntity::getBucketShardKey, bucketShardKey)
                            .eq(SeckillStockSnapshotEntity::getStatus, "RELEASING")
                            .set(SeckillStockSnapshotEntity::getStatus, "RELEASED")
                            .set(SeckillStockSnapshotEntity::getActiveKey, null)
                            .set(SeckillStockSnapshotEntity::getMessage, resultMessage)
                            .set(SeckillStockSnapshotEntity::getUpdatedAt, LocalDateTime.now()));
                    if (released == 0) {
                        throw new BusinessException(409, "Seckill stock snapshot release finalize failed");
                    }
                    snapshot.setStatus("RELEASED");
                    snapshot.setActiveKey(null);
                    snapshot.setMessage(resultMessage);
                    snapshot.setUpdatedAt(LocalDateTime.now());
                }
            }
            return new StockReleaseResult(toStockSnapshot(snapshot), stockVersion);
        } finally {
            totalSample.stop(releaseDeductionTotalTimer);
        }
    }

    private SeckillStockSnapshotEntity selectSnapshot(String requestId, Long bucketShardKey) {
        if (bucketShardKey == null) {
            return snapshotMapper.selectById(requestId);
        }
        return snapshotMapper.selectOne(Wrappers.<SeckillStockSnapshotEntity>lambdaQuery()
                .eq(SeckillStockSnapshotEntity::getRequestId, requestId)
                .eq(SeckillStockSnapshotEntity::getBucketShardKey, bucketShardKey));
    }

    public StockSnapshot findStockSnapshot(String requestId, Long bucketShardKey) {
        SeckillStockSnapshotEntity snapshot = selectSnapshot(requestId, bucketShardKey);
        return snapshot == null ? null : toStockSnapshot(snapshot);
    }

    public Map<String, StockSnapshot> findStockSnapshots(List<String> requestIds, Long bucketShardKey) {
        if (requestIds == null || requestIds.isEmpty() || bucketShardKey == null) {
            return Map.of();
        }
        return snapshotMapper.selectByRequestIdsAndShard(requestIds, bucketShardKey).stream()
                .collect(Collectors.toMap(
                        SeckillStockSnapshotEntity::getRequestId,
                        this::toStockSnapshot,
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    public Map<ActivitySkuKey, SeckillSku> findSkusByActivityAndSkuIds(Map<Long, Set<Long>> activitySkuIds) {
        if (activitySkuIds == null || activitySkuIds.isEmpty()) {
            return Map.of();
        }
        Map<ActivitySkuKey, SeckillSku> result = new LinkedHashMap<>();
        for (Map.Entry<Long, Set<Long>> entry : activitySkuIds.entrySet()) {
            Long activityId = entry.getKey();
            Set<Long> skuIds = entry.getValue();
            if (activityId == null || skuIds == null || skuIds.isEmpty()) {
                continue;
            }
            skuMapper.selectByActivityIdAndSkuIds(activityId, List.copyOf(skuIds))
                    .forEach(entity -> result.put(new ActivitySkuKey(entity.getActivityId(), entity.getSkuId()), toDomain(entity)));
        }
        return result;
    }

    public void saveResult(SeckillResult result) {
        resultSaveTimer.record(() -> doSaveResult(result));
    }

    private void doSaveResult(SeckillResult result) {
        SeckillResultEntity entity = new SeckillResultEntity();
        entity.setRequestId(result.requestId());
        entity.setStatus(result.status());
        entity.setOrderSn(result.orderSn());
        entity.setMessage(truncate(result.message(), MESSAGE_MAX_LENGTH));
        entity.setUpdatedAt(LocalDateTime.now());
        try {
            resultMapper.insert(entity);
        } catch (DuplicateKeyException exception) {
            resultMapper.updateById(entity);
        }
    }

    public SeckillResult result(String requestId) {
        SeckillResultEntity entity = resultMapper.selectById(requestId);
        if (entity == null) {
            return new SeckillResult(requestId, "PROCESSING", null, "Processing");
        }
        return new SeckillResult(entity.getRequestId(), entity.getStatus(), entity.getOrderSn(), entity.getMessage());
    }

    public Map<String, Integer> stockSnapshot() {
        return skuMapper.selectList(Wrappers.emptyWrapper()).stream()
                .collect(Collectors.toMap(
                        sku -> sku.getActivityId() + ":" + sku.getSkuId(),
                        SeckillSkuEntity::getStock
                ));
    }

    private SeckillSku toDomain(SeckillSkuEntity entity) {
        return new SeckillSku(
                entity.getId(),
                entity.getActivityId(),
                entity.getSkuId(),
                entity.getSkuName(),
                entity.getSeckillPrice(),
                entity.getStock()
        );
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? Instant.now() : value.atZone(ZONE_ID).toInstant();
    }

    private StockSnapshot toStockSnapshot(SeckillStockSnapshotEntity entity) {
        return new StockSnapshot(
                entity.getRequestId(),
                entity.getActivityId(),
                entity.getSkuId(),
                entity.getUserId(),
                entity.getQuantity(),
                entity.getStatus()
        );
    }

    private Long resolveSnapshotStockId(SeckillStockSnapshotEntity snapshot) {
        if (snapshot.getStockId() != null) {
            return snapshot.getStockId();
        }
        SeckillSkuEntity sku = skuMapper.selectOne(Wrappers.<SeckillSkuEntity>lambdaQuery()
                .eq(SeckillSkuEntity::getActivityId, snapshot.getActivityId())
                .eq(SeckillSkuEntity::getSkuId, snapshot.getSkuId()));
        if (sku == null) {
            throw new BusinessException(409, "Seckill snapshot stock id missing");
        }
        return sku.getId();
    }

    private Timer timer(String name, String description) {
        return Timer.builder(name)
                .description(description)
                .register(meterRegistry);
    }

    private boolean bucketModeEnabled() {
        return bucketService != null && properties.getBucket().isEnabled();
    }

    private boolean hasDeductChangeLog(String requestId, Long bucketShardKey) {
        return changeLogMapper != null
                && bucketShardKey != null
                && changeLogMapper.countByRequestIdAndChangeTypeAndBucketShardKey(requestId, "DEDUCT", bucketShardKey) > 0;
    }

    public boolean isBucketModeEnabled() {
        return bucketModeEnabled();
    }

    private String messageOrDefault(String message, String defaultMessage) {
        return truncate(message == null ? defaultMessage : message, MESSAGE_MAX_LENGTH);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    public record StockSnapshot(String requestId,
                                Long activityId,
                                Long skuId,
                                Long userId,
                                Integer quantity,
                                String status) {
    }

    public record ActivitySkuKey(Long activityId, Long skuId) {
    }
}

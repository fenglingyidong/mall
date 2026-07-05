package com.mall.seckill.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.common.exception.BusinessException;
import com.mall.seckill.pojo.entity.SeckillActivity;
import com.mall.seckill.pojo.entity.SeckillActivityEntity;
import com.mall.seckill.pojo.entity.SeckillResultEntity;
import com.mall.seckill.pojo.entity.SeckillStockSnapshotEntity;
import com.mall.seckill.pojo.entity.SeckillSku;
import com.mall.seckill.pojo.entity.SeckillSkuEntity;
import com.mall.seckill.pojo.vo.SeckillActivityView;
import com.mall.seckill.pojo.vo.SeckillResult;
import com.mall.seckill.pojo.vo.StockDeductProbeResponse;
import com.mall.seckill.pojo.vo.StockDeductionResult;
import com.mall.seckill.pojo.vo.StockReleaseResult;
import com.mall.seckill.pojo.vo.StockVersion;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Repository
public class SeckillRepository {

    private static final ZoneId ZONE_ID = ZoneId.systemDefault();

    private final SeckillActivityMapper activityMapper;
    private final SeckillSkuMapper skuMapper;
    private final SeckillResultMapper resultMapper;
    private final SeckillStockSnapshotMapper snapshotMapper;
    private final MeterRegistry meterRegistry;
    private final Timer stockDeductTotalTimer;
    private final Timer stockDeductUpdateTimer;
    private final Timer stockDeductSelectTimer;
    private final Timer stockDeductUpdateOnlyTotalTimer;
    private final Timer stockDeductUpdateOnlyUpdateTimer;
    private final Timer recordDeductionTotalTimer;
    private final Timer recordDeductionDuplicateTimer;
    private final Timer recordDeductionSnapshotInsertTimer;
    private final Timer recordDeductionStockUpdateTimer;
    private final Timer recordDeductionStockSelectTimer;
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
        this.activityMapper = activityMapper;
        this.skuMapper = skuMapper;
        this.resultMapper = resultMapper;
        this.snapshotMapper = snapshotMapper;
        this.meterRegistry = meterRegistry;
        this.stockDeductTotalTimer = timer("seckill.stock.deduct.total", "Stock-only load test total latency");
        this.stockDeductUpdateTimer = timer("seckill.stock.deduct.update", "Stock-only load test update latency");
        this.stockDeductSelectTimer = timer("seckill.stock.deduct.select", "Stock-only load test stock/version select latency");
        this.stockDeductUpdateOnlyTotalTimer = timer("seckill.stock.deduct.update-only.total", "Stock-only load test MyBatis update-only total latency");
        this.stockDeductUpdateOnlyUpdateTimer = timer("seckill.stock.deduct.update-only.update", "Stock-only load test MyBatis update-only update latency");
        this.recordDeductionTotalTimer = timer("seckill.submit.record.total", "Official submit record-deduction transaction latency");
        this.recordDeductionDuplicateTimer = timer("seckill.submit.record.duplicate", "Official submit duplicate ledger check latency");
        this.recordDeductionSnapshotInsertTimer = timer("seckill.submit.record.snapshot.insert", "Official submit stock snapshot insert latency");
        this.recordDeductionStockUpdateTimer = timer("seckill.submit.record.stock.update", "Official submit stock update latency");
        this.recordDeductionStockSelectTimer = timer("seckill.submit.record.stock.select", "Official submit stock version select latency");
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

    public boolean hasActiveDeduction(Long activityId, Long userId) {
        Long count = snapshotMapper.selectCount(Wrappers.<SeckillStockSnapshotEntity>lambdaQuery()
                .eq(SeckillStockSnapshotEntity::getActivityId, activityId)
                .eq(SeckillStockSnapshotEntity::getActiveKey, userId));
        return count != null && count > 0;
    }

    @Transactional(rollbackFor = Exception.class)
    public StockDeductionResult recordDeduction(String requestId, Long stockId, Long activityId, Long skuId, Long userId, int quantity) {
        return recordDeduction(requestId, stockId, activityId, skuId, userId, quantity, () -> {
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public StockDeductionResult recordDeduction(String requestId,
                                                Long stockId,
                                                Long activityId,
                                                Long skuId,
                                                Long userId,
                                                int quantity,
                                                Runnable beforeStockUpdate) {
        Timer.Sample totalSample = Timer.start(meterRegistry);
        try {
            if (recordDeductionDuplicateTimer.record(() -> hasActiveDeduction(activityId, userId))) {
                return StockDeductionResult.duplicate();
            }
            SeckillStockSnapshotEntity snapshot = new SeckillStockSnapshotEntity();
            snapshot.setRequestId(requestId);
            snapshot.setStockId(stockId);
            snapshot.setActivityId(activityId);
            snapshot.setSkuId(skuId);
            snapshot.setUserId(userId);
            snapshot.setActiveKey(userId);
            snapshot.setQuantity(quantity);
            snapshot.setStatus("DEDUCTED");
            snapshot.setMessage("Stock deducted");
            LocalDateTime now = LocalDateTime.now();
            snapshot.setCreatedAt(now);
            snapshot.setUpdatedAt(now);
            try {
                recordDeductionSnapshotInsertTimer.record(() -> snapshotMapper.insert(snapshot));
            } catch (DuplicateKeyException exception) {
                return StockDeductionResult.duplicate();
            }

            beforeStockUpdate.run();
            if (recordDeductionStockUpdateTimer.record(() -> skuMapper.deductStockAndIncreaseVersionById(stockId, quantity)) == 0) {
                throw new SeckillStockNotEnoughException();
            }
            StockVersion stockVersion = recordDeductionStockSelectTimer.record(() -> skuMapper.selectStockVersionById(stockId));
            saveResult(new SeckillResult(requestId, "PROCESSING", null, "Processing"));
            return StockDeductionResult.success(stockVersion);
        } finally {
            totalSample.stop(recordDeductionTotalTimer);
        }
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

    @Transactional(rollbackFor = Exception.class)
    public StockSnapshot confirmDeduction(String requestId, String orderSn, String message) {
        SeckillStockSnapshotEntity snapshot = snapshotMapper.selectById(requestId);
        if (snapshot == null) {
            return null;
        }
        if ("DEDUCTED".equals(snapshot.getStatus())) {
            LocalDateTime now = LocalDateTime.now();
            int updated = snapshotMapper.update(null, Wrappers.<SeckillStockSnapshotEntity>lambdaUpdate()
                    .eq(SeckillStockSnapshotEntity::getRequestId, requestId)
                    .eq(SeckillStockSnapshotEntity::getStatus, "DEDUCTED")
                    .set(SeckillStockSnapshotEntity::getStatus, "CONFIRMED")
                    .set(SeckillStockSnapshotEntity::getOrderSn, orderSn)
                    .set(SeckillStockSnapshotEntity::getMessage, message == null ? "Order created" : message)
                    .set(SeckillStockSnapshotEntity::getUpdatedAt, now));
            if (updated > 0) {
                snapshot.setStatus("CONFIRMED");
                snapshot.setOrderSn(orderSn);
                snapshot.setMessage(message == null ? "Order created" : message);
                snapshot.setUpdatedAt(now);
            }
        }
        return toStockSnapshot(snapshot);
    }

    @Transactional(rollbackFor = Exception.class)
    public StockReleaseResult releaseDeduction(String requestId, String message) {
        Timer.Sample totalSample = Timer.start(meterRegistry);
        try {
            SeckillStockSnapshotEntity snapshot = snapshotMapper.selectById(requestId);
            if (snapshot == null) {
                return null;
            }
            StockVersion stockVersion = null;
            if ("DEDUCTED".equals(snapshot.getStatus())) {
                LocalDateTime now = LocalDateTime.now();
                int updated = releaseDeductionSnapshotUpdateTimer.record(() -> snapshotMapper.update(null, Wrappers.<SeckillStockSnapshotEntity>lambdaUpdate()
                        .eq(SeckillStockSnapshotEntity::getRequestId, requestId)
                        .eq(SeckillStockSnapshotEntity::getStatus, "DEDUCTED")
                        .set(SeckillStockSnapshotEntity::getStatus, "RELEASED")
                        .set(SeckillStockSnapshotEntity::getActiveKey, null)
                        .set(SeckillStockSnapshotEntity::getMessage, message == null ? "Stock released" : message)
                        .set(SeckillStockSnapshotEntity::getUpdatedAt, now)));
                if (updated > 0) {
                    Long stockId = resolveSnapshotStockId(snapshot);
                    if (releaseDeductionStockUpdateTimer.record(() -> skuMapper.releaseStockAndIncreaseVersionById(stockId, snapshot.getQuantity())) == 0) {
                        throw new BusinessException(409, "Seckill stock ledger release failed");
                    }
                    stockVersion = releaseDeductionStockSelectTimer.record(() -> skuMapper.selectStockVersionById(stockId));
                    snapshot.setStatus("RELEASED");
                    snapshot.setActiveKey(null);
                    snapshot.setMessage(message == null ? "Stock released" : message);
                    snapshot.setUpdatedAt(now);
                }
            }
            return new StockReleaseResult(toStockSnapshot(snapshot), stockVersion);
        } finally {
            totalSample.stop(releaseDeductionTotalTimer);
        }
    }

    public void saveResult(SeckillResult result) {
        resultSaveTimer.record(() -> doSaveResult(result));
    }

    private void doSaveResult(SeckillResult result) {
        SeckillResultEntity entity = new SeckillResultEntity();
        entity.setRequestId(result.requestId());
        entity.setStatus(result.status());
        entity.setOrderSn(result.orderSn());
        entity.setMessage(result.message());
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

    public record StockSnapshot(String requestId,
                                Long activityId,
                                Long skuId,
                                Long userId,
                                Integer quantity,
                                String status) {
    }
}

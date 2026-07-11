package com.mall.seckill.service.impl;

import com.mall.common.context.UserContext;
import com.mall.common.exception.BusinessException;
import com.mall.seckill.cache.SeckillStockCache;
import com.mall.seckill.config.SeckillProperties;
import com.mall.seckill.mapper.SeckillRepository;
import com.mall.seckill.mapper.SeckillStockNotEnoughException;
import com.mall.seckill.pojo.entity.SeckillActivity;
import com.mall.seckill.pojo.entity.SeckillSku;
import com.mall.seckill.pojo.vo.SeckillActivityView;
import com.mall.seckill.pojo.vo.SeckillResult;
import com.mall.seckill.pojo.vo.SeckillSubmitResponse;
import com.mall.seckill.pojo.vo.StockDeductionResult;
import com.mall.seckill.service.SeckillService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.UUID;

@Service
public class SeckillServiceImpl implements SeckillService {

    private static final int DEDUCT_SUCCESS = 0;
    private static final int STOCK_NOT_ENOUGH = 1;
    private static final int DUPLICATE_PURCHASE = 2;
    private static final int SECKILL_QUANTITY = 1;

    private final SeckillRepository repository;
    private final SeckillStockCache stockCache;
    private final SentinelSeckillGuard sentinelGuard;
    private final SeckillHotspotGuard hotspotGuard;
    private final SeckillEntryGuard entryGuard;
    private final RedissonClient redissonClient;
    private final SeckillProperties properties;
    private final MeterRegistry meterRegistry;
    private final Timer submitTotalTimer;
    private final Timer submitSentinelTimer;
    private final Timer submitMetadataTimer;
    private final Timer submitLockTimer;
    private final Timer submitStockCacheSoldOutTimer;
    private final ConcurrentMap<Long, CacheEntry<SeckillActivity>> activityCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CacheEntry<SeckillSku>> skuCache = new ConcurrentHashMap<>();

    /**
     * 创建秒杀服务，并初始化提交链路各阶段的耗时指标。
     */
    @Autowired
    public SeckillServiceImpl(SeckillRepository repository,
                              SeckillStockCache stockCache,
                              SentinelSeckillGuard sentinelGuard,
                              SeckillHotspotGuard hotspotGuard,
                              ObjectProvider<RedissonClient> redissonClient,
                              SeckillProperties properties,
                              MeterRegistry meterRegistry,
                              SeckillEntryGuard entryGuard) {
        this.repository = repository;
        this.stockCache = stockCache;
        this.sentinelGuard = sentinelGuard;
        this.hotspotGuard = hotspotGuard;
        this.entryGuard = entryGuard;
        this.redissonClient = redissonClient.getIfAvailable();
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.submitTotalTimer = timer("seckill.submit.total", "Official seckill submit total latency");
        this.submitSentinelTimer = timer("seckill.submit.sentinel", "Official seckill submit Sentinel guard latency");
        this.submitMetadataTimer = timer("seckill.submit.metadata", "Official seckill submit metadata load latency");
        this.submitLockTimer = timer("seckill.submit.lock", "Official seckill submit duplicate lock latency");
        this.submitStockCacheSoldOutTimer = timer("seckill.submit.stock-cache.sold-out", "Official seckill submit stock-cache sold-out check latency");
    }

    /**
     * 查询 API 层可展示的秒杀活动列表。
     */
    @Override
    public List<SeckillActivityView> activities() {
        return repository.activityViews();
    }

    /**
     * HTTP 秒杀提交主入口。
     *
     * <p>该方法只做请求规范化、限流、活动校验和入口保护，然后交给异步入口链路写入
     * snapshot/change_log 事实并返回 {@code PROCESSING}。正式订单由后台根据 change_log 异步创建。</p>
     */
    @Override
    public SeckillSubmitResponse submit(Long activityId, Long skuId, String requestId) {
        Timer.Sample totalSample = Timer.start(meterRegistry);
        try {
            // 先规范化幂等号，确保后续所有事实表都使用同一个 requestId。
            String normalizedRequestId = normalizeRequestId(requestId);

            // 快速准入控制：热点判断和 Sentinel 限流要早于元数据加载与数据库写入。
            boolean hotspot = hotspotGuard.isHotspot(activityId, skuId);
            submitSentinelTimer.record(() -> sentinelGuard.checkSubmit(hotspot));

            // 加载用户和活动/SKU 元数据，并拒绝不在活动窗口内的请求。
            Long userId = UserContext.currentUserIdOrDefault(1L);
            SubmitMetadata metadata = submitMetadataTimer.record(() -> loadMetadata(activityId, skuId));
            if (!metadata.activity().activeAt(Instant.now())) {
                throw new BusinessException(400, "Seckill activity is not active");
            }

            // Redisson 短锁只是兼容性的同用户保护；当前链路仍强制依赖异步 entry guard。
            if (redissonClient == null || !properties.getLock().isEnabled()) {
                return doSubmitWithHotspotPermit(
                        activityId,
                        skuId,
                        userId,
                        metadata.sku(),
                        metadata.activity().endAt(),
                        normalizedRequestId);
            }
            RLock lock = redissonClient.getLock(lockKey(activityId, skuId, userId));
            boolean locked;
            try {
                locked = tryLockWithTimer(lock);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new BusinessException(429, "Seckill submit interrupted");
            }
            if (!locked) {
                throw new BusinessException(429, "Duplicate seckill submit");
            }
            try {
                // 真正的库存事实写入在热点 permit 和 Redis entry guard 之后执行。
                return doSubmitWithHotspotPermit(
                        activityId,
                        skuId,
                        userId,
                        metadata.sku(),
                        metadata.activity().endAt(),
                        normalizedRequestId);
            } finally {
                unlockIfHeldByCurrentThread(lock);
            }
        } finally {
            totalSample.stop(submitTotalTimer);
        }
    }

    /**
     * 一次性加载活动和 SKU 元数据，便于提交链路统一统计元数据阶段耗时。
     */
    private SubmitMetadata loadMetadata(Long activityId, Long skuId) {
        SeckillActivity activity = requireActivity(activityId);
        SeckillSku sku = requireSku(activityId, skuId);
        return new SubmitMetadata(activity, sku);
    }

    /**
     * 在不改变 Redisson 锁行为的前提下记录等待耗时。
     */
    private boolean tryLockWithTimer(RLock lock) throws InterruptedException {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return tryLock(lock);
        } finally {
            sample.stop(submitLockTimer);
        }
    }

    /**
     * 按配置的等待时间和租约时间尝试获取可选的同用户提交锁。
     */
    private boolean tryLock(RLock lock) throws InterruptedException {
        long waitMillis = properties.getLock().getWaitMillis();
        long leaseMillis = properties.getLock().getLeaseMillis();
        if (leaseMillis > 0) {
            return lock.tryLock(waitMillis, leaseMillis, TimeUnit.MILLISECONDS);
        }
        return lock.tryLock(waitMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 释放可选 Redisson 锁；锁已过期时不把请求结果改成失败。
     */
    private void unlockIfHeldByCurrentThread(RLock lock) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (IllegalMonitorStateException ignored) {
            // 锁租约晚于业务返回过期时，不应把请求结果变成 HTTP 500。
        }
    }

    /**
     * 确认当前提交链路只能运行在异步入口和分桶模式下。
     */
    private SeckillSubmitResponse doSubmit(Long activityId,
                                           Long skuId,
                                           Long userId,
                                           SeckillSku sku,
                                           Instant activityEndAt,
                                           String requestId) {
        if (!asyncEntryGuardEnabled()) {
            throw new BusinessException(500, "Seckill async entry guard is not enabled");
        }
        return doSubmitWithAsyncEntry(requestId, activityId, skuId, userId, sku, activityEndAt);
    }

    /**
     * 写入异步秒杀提交事实。
     *
     * <p>这是准入控制后的热路径：先用 Redis entry key 做快速挡重，再写 snapshot 和分桶扣减事实。
     * 本方法不直接创建订单 outbox，订单消息由 change_log worker 负责生成。</p>
     */
    private SeckillSubmitResponse doSubmitWithAsyncEntry(String requestId,
                                                         Long activityId,
                                                         Long skuId,
                                                         Long userId,
                                                         SeckillSku sku,
                                                         Instant activityEndAt) {
        // request key 处理同一请求重试；重复请求直接读取已有结果投影。
        SeckillEntryGuard.RequestDecision requestDecision = entryGuard.acquireRequest(requestId);
        if (requestDecision.outcome() == SeckillEntryGuard.RequestOutcome.DUPLICATE) {
            return responseFromExistingResultOrProcessing(requestId);
        }

        // buyer key 在触碰分桶库存前拦住同一买家的并发提交。
        SeckillEntryGuard.BuyerDecision buyerDecision =
                entryGuard.acquireBuyer(activityId, skuId, userId, requestId, activityEndAt);
        if (buyerDecision.outcome() == SeckillEntryGuard.BuyerOutcome.SAME_REQUEST) {
            return responseFromExistingResultOrProcessing(requestId);
        }
        if (buyerDecision.outcome() == SeckillEntryGuard.BuyerOutcome.DUPLICATE_BUYER) {
            return failedSubmit(requestId, DUPLICATE_PURCHASE);
        }

        // 库存缓存只做售罄快速失败；数据库分桶库存仍是权威事实。
        if (submitStockCacheSoldOutTimer.record(() -> stockCache.isSoldOut(activityId, skuId))) {
            entryGuard.releaseBuyer(activityId, skuId, userId, requestId);
            return failedSubmit(requestId, STOCK_NOT_ENOUGH);
        }

        // 选择一个可用业务桶；所有桶售罄时在创建 snapshot 前失败。
        SeckillBucketService.SelectedBucket selectedBucket;
        try {
            selectedBucket = repository.selectBucket(activityId, skuId);
        } catch (SeckillStockNotEnoughException exception) {
            entryGuard.releaseBuyer(activityId, skuId, userId, requestId);
            return failedSubmit(requestId, STOCK_NOT_ENOUGH);
        }

        // snapshot 是请求级事实，也携带后续结果闭环需要的 bucket shard key。
        SeckillRepository.SnapshotRegistration snapshotRegistration = repository.registerBucketSnapshot(
                requestId,
                sku.id(),
                activityId,
                skuId,
                userId,
                SECKILL_QUANTITY,
                selectedBucket);
        if (snapshotRegistration.outcome() == SeckillRepository.SnapshotRegistrationOutcome.REQUEST_DUPLICATE) {
            return responseFromExistingResultOrProcessing(requestId);
        }
        if (snapshotRegistration.outcome() == SeckillRepository.SnapshotRegistrationOutcome.ACTIVE_DUPLICATE) {
            entryGuard.releaseBuyer(activityId, skuId, userId, requestId);
            return failedSubmit(requestId, DUPLICATE_PURCHASE);
        }

        // 分桶扣减和 change_log 插入是库存事实，并驱动后续异步订单 outbox 创建。
        StockDeductionResult deductionResult;
        try {
            deductionResult = repository.recordBucketDeductionFact(
                    requestId,
                    activityId,
                    skuId,
                    selectedBucket,
                    SECKILL_QUANTITY);
        } catch (SeckillStockNotEnoughException exception) {
            entryGuard.releaseBuyer(activityId, skuId, userId, requestId);
            repository.markRegisteredSnapshotFailed(requestId, "Stock not enough");
            return failedSubmit(requestId, STOCK_NOT_ENOUGH);
        }

        // snapshot 登记后的失败必须关闭 REGISTERED snapshot，并释放 buyer key。
        if (deductionResult.code() == DUPLICATE_PURCHASE) {
            entryGuard.releaseBuyer(activityId, skuId, userId, requestId);
            repository.markRegisteredSnapshotFailed(requestId, "Duplicate purchase");
            return failedSubmit(requestId, DUPLICATE_PURCHASE);
        }
        if (deductionResult.code() != DEDUCT_SUCCESS) {
            entryGuard.releaseBuyer(activityId, skuId, userId, requestId);
            repository.markRegisteredSnapshotFailed(requestId, "Seckill failed");
            return failedSubmit(requestId, deductionResult.code());
        }
        return processingResponse(requestId);
    }

    /**
     * 用本地热点并发 permit 包裹提交链路。
     */
    private SeckillSubmitResponse doSubmitWithHotspotPermit(Long activityId,
                                                            Long skuId,
                                                            Long userId,
                                                            SeckillSku sku,
                                                            Instant activityEndAt,
                                                            String requestId) {
        try (SeckillHotspotGuard.HotspotPermit ignored = hotspotGuard.acquire(activityId, skuId)) {
            return doSubmit(activityId, skuId, userId, sku, activityEndAt, requestId);
        }
    }

    /**
     * 在预期流量峰值前预热元数据和库存版本缓存。
     */
    public void prewarm(Long activityId, Long skuId) {
        SubmitMetadata metadata = loadMetadata(activityId, skuId);
        stockCache.refresh(activityId, skuId, repository.stockVersion(metadata.sku().id(), activityId, skuId));
    }

    /**
     * 通过短 TTL 本地缓存加载秒杀活动。
     */
    private SeckillActivity requireActivity(Long activityId) {
        return cached(activityCache, activityId, () -> repository.requireActivity(activityId));
    }

    /**
     * 通过短 TTL 本地缓存加载活动 SKU。
     */
    private SeckillSku requireSku(Long activityId, Long skuId) {
        return cached(skuCache, activityId + ":" + skuId, () -> repository.requireSku(activityId, skuId));
    }

    /**
     * 热提交元数据使用的小型 TTL 缓存，不参与库存事实判断。
     */
    private <K, T> T cached(ConcurrentMap<K, CacheEntry<T>> cache, K key, Supplier<T> loader) {
        long ttlMillis = properties.getMetadataCacheTtlMillis();
        if (ttlMillis <= 0) {
            return loader.get();
        }
        long now = System.currentTimeMillis();
        CacheEntry<T> current = cache.get(key);
        if (current != null && current.isValid(now)) {
            return current.value();
        }

        // 元数据允许并发重复加载，换取更简单的缓存逻辑。
        T value = loader.get();
        cache.put(key, new CacheEntry<>(value, now + ttlMillis));
        return value;
    }

    /**
     * 持久化终态失败结果，并返回已知提交失败的 API 响应。
     */
    private SeckillSubmitResponse failedSubmit(String requestId, int result) {
        if (result == STOCK_NOT_ENOUGH) {
            repository.saveResult(new SeckillResult(requestId, "FAILED", null, "Stock not enough"));
            return new SeckillSubmitResponse(requestId, "FAILED", "Stock not enough");
        }
        if (result == DUPLICATE_PURCHASE) {
            repository.saveResult(new SeckillResult(requestId, "FAILED", null, "Duplicate purchase"));
            return new SeckillSubmitResponse(requestId, "FAILED", "Duplicate purchase");
        }
        repository.saveResult(new SeckillResult(requestId, "FAILED", null, "Seckill failed"));
        return new SeckillSubmitResponse(requestId, "FAILED", "Seckill failed");
    }

    /**
     * 重复请求优先返回已持久化结果；异步闭环未完成时返回 PROCESSING。
     */
    private SeckillSubmitResponse responseFromExistingResultOrProcessing(String requestId) {
        SeckillResult result = repository.result(requestId);
        if (result == null) {
            return processingResponse(requestId);
        }
        return new SeckillSubmitResponse(result.requestId(), result.status(), result.message());
    }

    /**
     * 构造订单创建完成前使用的异步受理响应。
     */
    private SeckillSubmitResponse processingResponse(String requestId) {
        return new SeckillSubmitResponse(requestId, "PROCESSING", "Processing");
    }

    /**
     * 优先使用调用方传入的幂等号；缺失时自动生成。
     */
    private String normalizeRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return requestId.trim();
    }

    /**
     * 检查当前异步分桶提交架构的硬性前置条件。
     */
    private boolean asyncEntryGuardEnabled() {
        return entryGuard != null
                && entryGuard.enabled()
                && repository.isBucketModeEnabled();
    }

    /**
     * 构造可选 Redisson 同用户提交锁的 key。
     */
    private String lockKey(Long activityId, Long skuId, Long userId) {
        return "seckill:submit:lock:" + activityId + ":" + skuId + ":" + userId;
    }

    private record CacheEntry<T>(T value, long expiresAtMillis) {

        /**
         * 判断缓存值在指定时间点是否仍可使用。
         */
        private boolean isValid(long now) {
            return now < expiresAtMillis;
        }
    }

    private record SubmitMetadata(SeckillActivity activity, SeckillSku sku) {
    }

    /**
     * 为提交链路的一个阶段注册 Micrometer 计时器。
     */
    private Timer timer(String name, String description) {
        return Timer.builder(name)
                .description(description)
                .register(meterRegistry);
    }

    /**
     * 根据 requestId 读取用户可见的秒杀结果投影。
     */
    @Override
    public SeckillResult result(String requestId) {
        return repository.result(requestId);
    }
}

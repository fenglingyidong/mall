package com.mall.seckill.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.context.UserContext;
import com.mall.common.exception.BusinessException;
import com.mall.common.util.JsonUtils;
import com.mall.message.ReliableMessagePublisher;
import com.mall.seckill.cache.SeckillStockCache;
import com.mall.seckill.config.SeckillProperties;
import com.mall.seckill.mapper.SeckillRepository;
import com.mall.seckill.mapper.SeckillStockNotEnoughException;
import com.mall.seckill.pojo.dto.SeckillOrderRequest;
import com.mall.seckill.pojo.entity.SeckillActivity;
import com.mall.seckill.pojo.entity.SeckillSku;
import com.mall.seckill.pojo.vo.SeckillActivityView;
import com.mall.seckill.pojo.vo.SeckillResult;
import com.mall.seckill.pojo.vo.SeckillSubmitResponse;
import com.mall.seckill.pojo.vo.StockDeductionResult;
import com.mall.seckill.pojo.vo.StockReleaseResult;
import com.mall.seckill.service.SeckillService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
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
    private final ReliableMessagePublisher messagePublisher;
    private final ObjectMapper objectMapper;
    private final RedissonClient redissonClient;
    private final SeckillProperties properties;
    private final MeterRegistry meterRegistry;
    private final Timer submitTotalTimer;
    private final Timer submitSentinelTimer;
    private final Timer submitMetadataTimer;
    private final Timer submitLockTimer;
    private final Timer submitStockCacheSoldOutTimer;
    private final Timer submitStockCacheRefreshTimer;
    private final Timer submitMqPublishTimer;
    private final ConcurrentMap<Long, CacheEntry<SeckillActivity>> activityCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CacheEntry<SeckillSku>> skuCache = new ConcurrentHashMap<>();

    public SeckillServiceImpl(SeckillRepository repository,
                              SeckillStockCache stockCache,
                              SentinelSeckillGuard sentinelGuard,
                              ReliableMessagePublisher messagePublisher,
                              ObjectMapper objectMapper,
                              ObjectProvider<RedissonClient> redissonClient,
                              SeckillProperties properties,
                              MeterRegistry meterRegistry) {
        this.repository = repository;
        this.stockCache = stockCache;
        this.sentinelGuard = sentinelGuard;
        this.messagePublisher = messagePublisher;
        this.objectMapper = objectMapper;
        this.redissonClient = redissonClient.getIfAvailable();
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.submitTotalTimer = timer("seckill.submit.total", "Official seckill submit total latency");
        this.submitSentinelTimer = timer("seckill.submit.sentinel", "Official seckill submit Sentinel guard latency");
        this.submitMetadataTimer = timer("seckill.submit.metadata", "Official seckill submit metadata load latency");
        this.submitLockTimer = timer("seckill.submit.lock", "Official seckill submit duplicate lock latency");
        this.submitStockCacheSoldOutTimer = timer("seckill.submit.stock-cache.sold-out", "Official seckill submit stock-cache sold-out check latency");
        this.submitStockCacheRefreshTimer = timer("seckill.submit.stock-cache.refresh", "Official seckill submit stock-cache refresh latency");
        this.submitMqPublishTimer = timer("seckill.submit.mq.publish", "Official seckill submit MQ publish latency");
    }

    @Override
    public List<SeckillActivityView> activities() {
        return repository.activityViews();
    }

    @Override
    public SeckillSubmitResponse submit(Long activityId, Long skuId) {
        Timer.Sample totalSample = Timer.start(meterRegistry);
        try {
            submitSentinelTimer.record(sentinelGuard::checkSubmit);
            Long userId = UserContext.currentUserIdOrDefault(1L);
            SubmitMetadata metadata = submitMetadataTimer.record(() -> loadMetadata(activityId, skuId));
            if (!metadata.activity().activeAt(Instant.now())) {
                throw new BusinessException(400, "Seckill activity is not active");
            }
            if (redissonClient == null || !properties.getLock().isEnabled()) {
                return doSubmit(activityId, skuId, userId, metadata.sku());
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
                return doSubmit(activityId, skuId, userId, metadata.sku());
            } finally {
                unlockIfHeldByCurrentThread(lock);
            }
        } finally {
            totalSample.stop(submitTotalTimer);
        }
    }

    private SubmitMetadata loadMetadata(Long activityId, Long skuId) {
        SeckillActivity activity = requireActivity(activityId);
        SeckillSku sku = requireSku(activityId, skuId);
        return new SubmitMetadata(activity, sku);
    }

    private boolean tryLockWithTimer(RLock lock) throws InterruptedException {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return tryLock(lock);
        } finally {
            sample.stop(submitLockTimer);
        }
    }

    private boolean tryLock(RLock lock) throws InterruptedException {
        long waitMillis = properties.getLock().getWaitMillis();
        long leaseMillis = properties.getLock().getLeaseMillis();
        if (leaseMillis > 0) {
            return lock.tryLock(waitMillis, leaseMillis, TimeUnit.MILLISECONDS);
        }
        return lock.tryLock(waitMillis, TimeUnit.MILLISECONDS);
    }

    private void unlockIfHeldByCurrentThread(RLock lock) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (IllegalMonitorStateException ignored) {
            // The request result should not be turned into HTTP 500 by a late lock expiry.
        }
    }

    private SeckillSubmitResponse doSubmit(Long activityId, Long skuId, Long userId, SeckillSku sku) {
        String requestId = UUID.randomUUID().toString();
        if (submitStockCacheSoldOutTimer.record(() -> stockCache.isSoldOut(activityId, skuId))) {
            return failedSubmit(requestId, STOCK_NOT_ENOUGH);
        }
        StockDeductionResult deductionResult;
        try {
            deductionResult = repository.recordDeduction(requestId, sku.id(), activityId, skuId, userId, SECKILL_QUANTITY);
        } catch (SeckillStockNotEnoughException exception) {
            return failedSubmit(requestId, STOCK_NOT_ENOUGH);
        }
        if (deductionResult.code() != DEDUCT_SUCCESS) {
            return failedSubmit(requestId, deductionResult.code());
        }
        submitStockCacheRefreshTimer.record(() -> stockCache.refresh(activityId, skuId, deductionResult.stockVersion()));

        SeckillOrderRequest orderRequest = new SeckillOrderRequest(
                requestId,
                activityId,
                userId,
                skuId,
                sku.skuName(),
                sku.price(),
                SECKILL_QUANTITY
        );
        try {
            submitMqPublishTimer.record(() ->
                    messagePublisher.publishSeckillOrderCreate(requestId, JsonUtils.toJson(objectMapper, orderRequest)));
        } catch (RuntimeException exception) {
            StockReleaseResult releaseResult = repository.releaseDeduction(requestId, "Order message publish failed");
            if (releaseResult != null) {
                submitStockCacheRefreshTimer.record(() -> stockCache.refresh(activityId, skuId, releaseResult.stockVersion()));
            }
            repository.saveResult(new SeckillResult(requestId, "FAILED", null, "Order message publish failed"));
            return new SeckillSubmitResponse(requestId, "FAILED", "Order message publish failed");
        }
        return new SeckillSubmitResponse(requestId, "ACCEPTED", "Accepted");
    }

    private SeckillActivity requireActivity(Long activityId) {
        return cached(activityCache, activityId, () -> repository.requireActivity(activityId));
    }

    private SeckillSku requireSku(Long activityId, Long skuId) {
        return cached(skuCache, activityId + ":" + skuId, () -> repository.requireSku(activityId, skuId));
    }

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
        T value = loader.get();
        cache.put(key, new CacheEntry<>(value, now + ttlMillis));
        return value;
    }

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

    private String lockKey(Long activityId, Long skuId, Long userId) {
        return "seckill:submit:lock:" + activityId + ":" + skuId + ":" + userId;
    }

    private record CacheEntry<T>(T value, long expiresAtMillis) {

        private boolean isValid(long now) {
            return now < expiresAtMillis;
        }
    }

    private record SubmitMetadata(SeckillActivity activity, SeckillSku sku) {
    }

    private Timer timer(String name, String description) {
        return Timer.builder(name)
                .description(description)
                .register(meterRegistry);
    }

    @Override
    public SeckillResult result(String requestId) {
        return repository.result(requestId);
    }
}

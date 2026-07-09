package com.mall.seckill.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.context.UserContext;
import com.mall.common.exception.BusinessException;
import com.mall.common.util.JsonUtils;
import com.mall.message.ReliableMessagePublisher;
import com.mall.seckill.cache.SeckillStockCache;
import com.mall.seckill.config.SeckillProperties;
import com.mall.seckill.mapper.ReservationGuardRepository;
import com.mall.seckill.mapper.SeckillRepository;
import com.mall.seckill.mapper.SeckillStockNotEnoughException;
import com.mall.seckill.pojo.entity.SeckillReservationGuardEntity;
import com.mall.seckill.pojo.dto.SeckillOrderRequest;
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
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLTransientException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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
    private static final int DEDUCTION_RETRY_ATTEMPTS = 3;
    private static final long DEDUCTION_RETRY_BACKOFF_MILLIS = 10L;

    private final SeckillRepository repository;
    private final SeckillStockCache stockCache;
    private final SentinelSeckillGuard sentinelGuard;
    private final SeckillHotspotGuard hotspotGuard;
    private final ReliableMessagePublisher messagePublisher;
    private final ReservationGuardRepository guardRepository;
    private final SeckillEntryGuard entryGuard;
    private final ObjectMapper objectMapper;
    private final RedissonClient redissonClient;
    private final SeckillProperties properties;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate submitTransactionTemplate;
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
                              SeckillHotspotGuard hotspotGuard,
                              ReliableMessagePublisher messagePublisher,
                              ObjectProvider<ReservationGuardRepository> guardRepository,
                              ObjectMapper objectMapper,
                              ObjectProvider<RedissonClient> redissonClient,
                              SeckillProperties properties,
                              MeterRegistry meterRegistry,
                              ObjectProvider<PlatformTransactionManager> transactionManager) {
        this(repository,
                stockCache,
                sentinelGuard,
                hotspotGuard,
                messagePublisher,
                guardRepository,
                objectMapper,
                redissonClient,
                properties,
                meterRegistry,
                transactionManager,
                null);
    }

    @Autowired
    public SeckillServiceImpl(SeckillRepository repository,
                              SeckillStockCache stockCache,
                              SentinelSeckillGuard sentinelGuard,
                              SeckillHotspotGuard hotspotGuard,
                              ReliableMessagePublisher messagePublisher,
                              ObjectProvider<ReservationGuardRepository> guardRepository,
                              ObjectMapper objectMapper,
                              ObjectProvider<RedissonClient> redissonClient,
                              SeckillProperties properties,
                              MeterRegistry meterRegistry,
                              ObjectProvider<PlatformTransactionManager> transactionManager,
                              SeckillEntryGuard entryGuard) {
        this.repository = repository;
        this.stockCache = stockCache;
        this.sentinelGuard = sentinelGuard;
        this.hotspotGuard = hotspotGuard;
        this.messagePublisher = messagePublisher;
        this.guardRepository = guardRepository.getIfAvailable();
        this.entryGuard = entryGuard;
        this.objectMapper = objectMapper;
        this.redissonClient = redissonClient.getIfAvailable();
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        PlatformTransactionManager manager = transactionManager.getIfAvailable();
        this.submitTransactionTemplate = manager == null ? null : new TransactionTemplate(manager);
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
        return submit(activityId, skuId, null);
    }

    @Override
    public SeckillSubmitResponse submit(Long activityId, Long skuId, String requestId) {
        Timer.Sample totalSample = Timer.start(meterRegistry);
        try {
            String normalizedRequestId = normalizeRequestId(requestId);
            boolean hotspot = hotspotGuard.isHotspot(activityId, skuId);
            submitSentinelTimer.record(() -> sentinelGuard.checkSubmit(hotspot));
            Long userId = UserContext.currentUserIdOrDefault(1L);
            SubmitMetadata metadata = submitMetadataTimer.record(() -> loadMetadata(activityId, skuId));
            if (!metadata.activity().activeAt(Instant.now())) {
                throw new BusinessException(400, "Seckill activity is not active");
            }
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

    private SeckillSubmitResponse doSubmit(Long activityId,
                                           Long skuId,
                                           Long userId,
                                           SeckillSku sku,
                                           Instant activityEndAt,
                                           String requestId) {
        if (asyncEntryGuardEnabled()) {
            return doSubmitWithAsyncEntry(requestId, activityId, skuId, userId, sku, activityEndAt);
        }
        if (submitStockCacheSoldOutTimer.record(() -> stockCache.isSoldOut(activityId, skuId))) {
            return failedSubmit(requestId, STOCK_NOT_ENOUGH);
        }
        if (reservationGuardEnabled()) {
            return doSubmitWithReservationGuard(requestId, activityId, skuId, userId, sku);
        }
        SeckillOrderRequest orderRequest = new SeckillOrderRequest(
                requestId,
                activityId,
                userId,
                skuId,
                sku.skuName(),
                sku.price(),
                SECKILL_QUANTITY
        );
        StockDeductionResult deductionResult;
        try {
            deductionResult = recordDeductionAndEnqueueOrder(requestId, sku.id(), activityId, skuId, userId, orderRequest);
        } catch (SeckillStockNotEnoughException exception) {
            return failedSubmit(requestId, STOCK_NOT_ENOUGH);
        }
        if (deductionResult.code() != DEDUCT_SUCCESS) {
            return failedSubmit(requestId, deductionResult.code());
        }
        if (deductionResult.stockVersion() != null) {
            submitStockCacheRefreshTimer.record(() -> stockCache.refresh(activityId, skuId, deductionResult.stockVersion()));
        }
        return new SeckillSubmitResponse(requestId, "ACCEPTED", "Accepted");
    }

    private SeckillSubmitResponse doSubmitWithAsyncEntry(String requestId,
                                                         Long activityId,
                                                         Long skuId,
                                                         Long userId,
                                                         SeckillSku sku,
                                                         Instant activityEndAt) {
        SeckillEntryGuard.RequestDecision requestDecision = entryGuard.acquireRequest(requestId);
        if (requestDecision.outcome() == SeckillEntryGuard.RequestOutcome.DUPLICATE) {
            return responseFromExistingResultOrProcessing(requestId);
        }

        SeckillEntryGuard.BuyerDecision buyerDecision =
                entryGuard.acquireBuyer(activityId, skuId, userId, requestId, activityEndAt);
        if (buyerDecision.outcome() == SeckillEntryGuard.BuyerOutcome.SAME_REQUEST) {
            return responseFromExistingResultOrProcessing(requestId);
        }
        if (buyerDecision.outcome() == SeckillEntryGuard.BuyerOutcome.DUPLICATE_BUYER) {
            return duplicatePurchaseResponse(requestId);
        }

        SeckillBucketService.SelectedBucket selectedBucket;
        try {
            selectedBucket = repository.selectBucket(activityId, skuId);
        } catch (SeckillStockNotEnoughException exception) {
            entryGuard.releaseBuyer(activityId, skuId, userId, requestId);
            return stockNotEnoughResponse(requestId);
        }

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
            return duplicatePurchaseResponse(requestId);
        }

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
            return stockNotEnoughResponse(requestId);
        }
        if (deductionResult.code() == DUPLICATE_PURCHASE) {
            entryGuard.releaseBuyer(activityId, skuId, userId, requestId);
            repository.markRegisteredSnapshotFailed(requestId, "Duplicate purchase");
            return duplicatePurchaseResponse(requestId);
        }
        if (deductionResult.code() != DEDUCT_SUCCESS) {
            entryGuard.releaseBuyer(activityId, skuId, userId, requestId);
            repository.markRegisteredSnapshotFailed(requestId, "Seckill failed");
            return seckillFailedResponse(requestId);
        }
        return processingResponse(requestId);
    }

    private SeckillSubmitResponse doSubmitWithReservationGuard(String requestId,
                                                               Long activityId,
                                                               Long skuId,
                                                               Long userId,
                                                               SeckillSku sku) {
        ReservationGuardRepository.CreateGuardResult guardResult = guardRepository.createOrLoad(
                requestId,
                new ReservationGuardRepository.ReservationDraft(requestId, activityId, skuId, userId));
        if (guardResult.outcome() == ReservationGuardRepository.GuardCreateOutcome.REQUEST_DUPLICATE) {
            return responseFromExistingGuard(guardResult.guard());
        }
        if (guardResult.outcome() == ReservationGuardRepository.GuardCreateOutcome.ACTIVE_DUPLICATE) {
            return responseFromActiveDuplicate(guardResult.guard());
        }

        SeckillBucketService.SelectedBucket selectedBucket;
        try {
            selectedBucket = repository.selectBucket(activityId, skuId);
            if (!guardRepository.attachBucket(requestId, selectedBucket)) {
                return responseFromExistingGuard(guardRepository.findByRequestId(requestId));
            }
        } catch (SeckillStockNotEnoughException exception) {
            guardRepository.markFailedIfProcessing(requestId, "Stock not enough");
            return failedSubmit(requestId, STOCK_NOT_ENOUGH);
        }

        SeckillOrderRequest orderRequest = new SeckillOrderRequest(
                requestId,
                activityId,
                userId,
                skuId,
                sku.skuName(),
                sku.price(),
                SECKILL_QUANTITY,
                selectedBucket.bucketShardKey()
        );

        StockDeductionResult deductionResult;
        try {
            deductionResult = executeGuardedRecordDeductionAndEnqueueOrder(
                    requestId,
                    sku.id(),
                    activityId,
                    skuId,
                    userId,
                    selectedBucket,
                    orderRequest);
        } catch (SeckillStockNotEnoughException exception) {
            guardRepository.markFailedIfProcessing(requestId, "Stock not enough");
            return failedSubmit(requestId, STOCK_NOT_ENOUGH);
        } catch (RuntimeException exception) {
            throw exception;
        }

        if (deductionResult.code() == DUPLICATE_PURCHASE) {
            guardRepository.markFailedIfProcessing(requestId, "Duplicate purchase");
            return failedSubmit(requestId, DUPLICATE_PURCHASE);
        }
        if (deductionResult.code() != DEDUCT_SUCCESS) {
            guardRepository.markFailedIfProcessing(requestId, "Seckill failed");
            return failedSubmit(requestId, deductionResult.code());
        }
        guardRepository.markDeducted(requestId);
        if (deductionResult.stockVersion() != null) {
            submitStockCacheRefreshTimer.record(() -> stockCache.refresh(activityId, skuId, deductionResult.stockVersion()));
        }
        return new SeckillSubmitResponse(requestId, "ACCEPTED", "Accepted");
    }

    private StockDeductionResult recordDeductionAndEnqueueOrder(String requestId,
                                                                Long stockId,
                                                                Long activityId,
                                                                Long skuId,
                                                                Long userId,
                                                                SeckillOrderRequest orderRequest) {
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= DEDUCTION_RETRY_ATTEMPTS; attempt++) {
            try {
                return executeRecordDeductionAndEnqueueOrder(requestId, stockId, activityId, skuId, userId, orderRequest);
            } catch (RuntimeException exception) {
                if (!isRetryableDeductionException(exception) || attempt == DEDUCTION_RETRY_ATTEMPTS) {
                    throw exception;
                }
                lastException = exception;
                backoffBeforeDeductionRetry(attempt);
            }
        }
        throw lastException;
    }

    private StockDeductionResult executeRecordDeductionAndEnqueueOrder(String requestId,
                                                                       Long stockId,
                                                                       Long activityId,
                                                                       Long skuId,
                                                                       Long userId,
                                                                       SeckillOrderRequest orderRequest) {
        if (submitTransactionTemplate == null) {
            return doRecordDeductionAndEnqueueOrder(requestId, stockId, activityId, skuId, userId, orderRequest);
        }
        return submitTransactionTemplate.execute(status ->
                doRecordDeductionAndEnqueueOrder(requestId, stockId, activityId, skuId, userId, orderRequest));
    }

    private StockDeductionResult executeGuardedRecordDeductionAndEnqueueOrder(String requestId,
                                                                              Long stockId,
                                                                              Long activityId,
                                                                              Long skuId,
                                                                              Long userId,
                                                                              SeckillBucketService.SelectedBucket selectedBucket,
                                                                              SeckillOrderRequest orderRequest) {
        if (submitTransactionTemplate == null) {
            return doRecordDeductionAndEnqueueOrder(requestId, stockId, activityId, skuId, userId, selectedBucket, orderRequest);
        }
        return submitTransactionTemplate.execute(status ->
                doRecordDeductionAndEnqueueOrder(requestId, stockId, activityId, skuId, userId, selectedBucket, orderRequest));
    }

    private StockDeductionResult doRecordDeductionAndEnqueueOrder(String requestId,
                                                                  Long stockId,
                                                                  Long activityId,
                                                                  Long skuId,
                                                                  Long userId,
                                                                  SeckillOrderRequest orderRequest) {
        StockDeductionResult deductionResult = repository.recordDeduction(
                requestId,
                stockId,
                activityId,
                skuId,
                userId,
                SECKILL_QUANTITY,
                bucketShardKey -> submitMqPublishTimer.record(() ->
                        messagePublisher.enqueueSeckillOrderCreate(requestId,
                        JsonUtils.toJson(objectMapper, orderRequest.withBucketShardKey(bucketShardKey)),
                        bucketShardKey)));
        return deductionResult;
    }

    private StockDeductionResult doRecordDeductionAndEnqueueOrder(String requestId,
                                                                  Long stockId,
                                                                  Long activityId,
                                                                  Long skuId,
                                                                  Long userId,
                                                                  SeckillBucketService.SelectedBucket selectedBucket,
                                                                  SeckillOrderRequest orderRequest) {
        return repository.recordDeduction(
                requestId,
                stockId,
                activityId,
                skuId,
                userId,
                SECKILL_QUANTITY,
                selectedBucket,
                bucketShardKey -> submitMqPublishTimer.record(() ->
                        messagePublisher.enqueueSeckillOrderCreate(requestId,
                                JsonUtils.toJson(objectMapper, orderRequest.withBucketShardKey(bucketShardKey)),
                                bucketShardKey)));
    }

    private boolean isRetryableDeductionException(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof TransientDataAccessException || current instanceof SQLTransientException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("deadlock found when trying to get lock")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void backoffBeforeDeductionRetry(int attempt) {
        try {
            Thread.sleep(DEDUCTION_RETRY_BACKOFF_MILLIS * attempt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(429, "Seckill submit interrupted");
        }
    }

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

    public void prewarm(Long activityId, Long skuId) {
        SubmitMetadata metadata = loadMetadata(activityId, skuId);
        stockCache.refresh(activityId, skuId, repository.stockVersion(metadata.sku().id(), activityId, skuId));
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

    private SeckillSubmitResponse responseFromExistingResultOrProcessing(String requestId) {
        SeckillResult result = repository.result(requestId);
        if (result == null) {
            return processingResponse(requestId);
        }
        return new SeckillSubmitResponse(result.requestId(), result.status(), result.message());
    }

    private SeckillSubmitResponse processingResponse(String requestId) {
        return new SeckillSubmitResponse(requestId, "PROCESSING", "Processing");
    }

    private SeckillSubmitResponse stockNotEnoughResponse(String requestId) {
        return new SeckillSubmitResponse(requestId, "FAILED", "Stock not enough");
    }

    private SeckillSubmitResponse duplicatePurchaseResponse(String requestId) {
        return new SeckillSubmitResponse(requestId, "FAILED", "Duplicate purchase");
    }

    private SeckillSubmitResponse seckillFailedResponse(String requestId) {
        return new SeckillSubmitResponse(requestId, "FAILED", "Seckill failed");
    }

    private String normalizeRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return requestId.trim();
    }

    private SeckillSubmitResponse responseFromExistingGuard(SeckillReservationGuardEntity guard) {
        SeckillResult result = repository.result(guard.getRequestId());
        return new SeckillSubmitResponse(result.requestId(), result.status(), result.message());
    }

    private SeckillSubmitResponse responseFromActiveDuplicate(SeckillReservationGuardEntity guard) {
        if (ReservationGuardRepository.STATUS_CONFIRMED.equals(guard.getStatus())) {
            return new SeckillSubmitResponse(guard.getRequestId(), "FAILED", "Already purchased");
        }
        return new SeckillSubmitResponse(guard.getRequestId(), "FAILED", "Duplicate purchase");
    }

    private boolean reservationGuardEnabled() {
        return guardRepository != null
                && properties.getReservationGuard().isEnabled()
                && repository.isBucketModeEnabled();
    }

    private boolean asyncEntryGuardEnabled() {
        return entryGuard != null
                && entryGuard.enabled()
                && repository.isBucketModeEnabled();
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

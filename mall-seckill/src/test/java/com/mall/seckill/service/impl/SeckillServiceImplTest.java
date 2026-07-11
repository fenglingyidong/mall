package com.mall.seckill.service.impl;

import com.mall.common.context.UserContext;
import com.mall.common.context.UserInfo;
import com.mall.common.exception.BusinessException;
import com.mall.seckill.cache.SeckillStockCache;
import com.mall.seckill.config.SeckillProperties;
import com.mall.seckill.mapper.SeckillRepository;
import com.mall.seckill.mapper.SeckillStockNotEnoughException;
import com.mall.seckill.pojo.entity.SeckillActivity;
import com.mall.seckill.pojo.entity.SeckillSku;
import com.mall.seckill.pojo.vo.SeckillResult;
import com.mall.seckill.pojo.vo.SeckillSubmitResponse;
import com.mall.seckill.pojo.vo.StockDeductionResult;
import com.mall.seckill.pojo.vo.StockVersion;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillServiceImplTest {

    @Mock
    private SeckillRepository repository;

    @Mock
    private SeckillStockCache stockCache;

    @Mock
    private SentinelSeckillGuard sentinelGuard;

    @Mock
    private SeckillHotspotGuard hotspotGuard;

    @Mock
    private SeckillEntryGuard entryGuard;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void submitShouldRequireAsyncEntryGuard() {
        SeckillServiceImpl service = newService(60_000);
        SeckillActivity activity = activeActivity();
        SeckillSku sku = sku();
        when(repository.requireActivity(1L)).thenReturn(activity);
        when(repository.requireSku(1L, 1001L)).thenReturn(sku);

        UserContext.set(new UserInfo(101L, "u101"));

        assertThatThrownBy(() -> service.submit(1L, 1001L, "client-r1"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Seckill async entry guard is not enabled");

        verify(repository, never()).selectBucket(anyLong(), anyLong());
    }

    @Test
    void asyncEntrySubmitShouldRegisterSnapshotAndDeductionFactWithoutOrderOutbox() {
        SeckillServiceImpl service = newAsyncEntryService();
        SeckillActivity activity = activeActivity();
        SeckillSku sku = sku();
        SeckillBucketService.SelectedBucket selectedBucket = bucket();
        stubMetadata(activity, sku);
        stubAsyncEntryEnabled();
        when(entryGuard.acquireRequest("client-r1"))
                .thenReturn(new SeckillEntryGuard.RequestDecision(SeckillEntryGuard.RequestOutcome.ACQUIRED));
        when(entryGuard.acquireBuyer(eq(1L), eq(1001L), eq(101L), eq("client-r1"), eq(activity.endAt())))
                .thenReturn(new SeckillEntryGuard.BuyerDecision(SeckillEntryGuard.BuyerOutcome.ACQUIRED, null));
        when(repository.selectBucket(1L, 1001L)).thenReturn(selectedBucket);
        when(repository.registerBucketSnapshot("client-r1", 10L, 1L, 1001L, 101L, 1, selectedBucket))
                .thenReturn(snapshotCreated("client-r1"));
        when(repository.recordBucketDeductionFact("client-r1", 1L, 1001L, selectedBucket, 1))
                .thenReturn(StockDeductionResult.success(new StockVersion(49, 1L)));

        UserContext.set(new UserInfo(101L, "u101"));
        SeckillSubmitResponse response = service.submit(1L, 1001L, "client-r1");

        assertThat(response.requestId()).isEqualTo("client-r1");
        assertThat(response.status()).isEqualTo("PROCESSING");
        assertThat(response.message()).isEqualTo("Processing");
        verify(repository).registerBucketSnapshot("client-r1", 10L, 1L, 1001L, 101L, 1, selectedBucket);
        verify(repository).recordBucketDeductionFact("client-r1", 1L, 1001L, selectedBucket, 1);
        verify(repository, never()).saveResult(any());
    }

    @Test
    void asyncEntrySubmitShouldCacheMetadataForAcceptedEntries() {
        SeckillServiceImpl service = newAsyncEntryService(60_000);
        SeckillActivity activity = activeActivity();
        SeckillSku sku = sku();
        SeckillBucketService.SelectedBucket selectedBucket = bucket();
        stubMetadata(activity, sku);
        stubAsyncEntryEnabled();
        when(entryGuard.acquireRequest(anyString()))
                .thenReturn(new SeckillEntryGuard.RequestDecision(SeckillEntryGuard.RequestOutcome.ACQUIRED));
        when(entryGuard.acquireBuyer(eq(1L), eq(1001L), anyLong(), anyString(), eq(activity.endAt())))
                .thenReturn(new SeckillEntryGuard.BuyerDecision(SeckillEntryGuard.BuyerOutcome.ACQUIRED, null));
        when(repository.selectBucket(1L, 1001L)).thenReturn(selectedBucket);
        when(repository.registerBucketSnapshot(anyString(), eq(10L), eq(1L), eq(1001L), anyLong(), eq(1), eq(selectedBucket)))
                .thenAnswer(invocation -> snapshotCreated(invocation.getArgument(0)));
        when(repository.recordBucketDeductionFact(anyString(), eq(1L), eq(1001L), eq(selectedBucket), eq(1)))
                .thenReturn(StockDeductionResult.success(null));

        UserContext.set(new UserInfo(101L, "u101"));
        SeckillSubmitResponse first = service.submit(1L, 1001L, null);
        UserContext.set(new UserInfo(102L, "u102"));
        SeckillSubmitResponse second = service.submit(1L, 1001L, null);

        assertThat(first.status()).isEqualTo("PROCESSING");
        assertThat(second.status()).isEqualTo("PROCESSING");
        verify(repository, times(1)).requireActivity(1L);
        verify(repository, times(1)).requireSku(1L, 1001L);
        verify(repository, times(2)).registerBucketSnapshot(anyString(), eq(10L), eq(1L), eq(1001L), anyLong(), eq(1), eq(selectedBucket));
        verify(repository, times(2)).recordBucketDeductionFact(anyString(), eq(1L), eq(1001L), eq(selectedBucket), eq(1));
        verify(stockCache, never()).refresh(anyLong(), anyLong(), any());
    }

    @Test
    void asyncEntrySubmitShouldUseTrimmedRequestId() {
        SeckillServiceImpl service = newAsyncEntryService();
        SeckillActivity activity = activeActivity();
        SeckillSku sku = sku();
        SeckillBucketService.SelectedBucket selectedBucket = bucket();
        stubMetadata(activity, sku);
        stubAsyncEntryEnabled();
        when(entryGuard.acquireRequest("client-r1"))
                .thenReturn(new SeckillEntryGuard.RequestDecision(SeckillEntryGuard.RequestOutcome.ACQUIRED));
        when(entryGuard.acquireBuyer(eq(1L), eq(1001L), eq(101L), eq("client-r1"), eq(activity.endAt())))
                .thenReturn(new SeckillEntryGuard.BuyerDecision(SeckillEntryGuard.BuyerOutcome.ACQUIRED, null));
        when(repository.selectBucket(1L, 1001L)).thenReturn(selectedBucket);
        when(repository.registerBucketSnapshot("client-r1", 10L, 1L, 1001L, 101L, 1, selectedBucket))
                .thenReturn(snapshotCreated("client-r1"));
        when(repository.recordBucketDeductionFact("client-r1", 1L, 1001L, selectedBucket, 1))
                .thenReturn(StockDeductionResult.success(null));

        UserContext.set(new UserInfo(101L, "u101"));
        SeckillSubmitResponse response = service.submit(1L, 1001L, " client-r1 ");

        assertThat(response.requestId()).isEqualTo("client-r1");
        verify(entryGuard).acquireRequest("client-r1");
        verify(repository).registerBucketSnapshot("client-r1", 10L, 1L, 1001L, 101L, 1, selectedBucket);
    }

    @Test
    void asyncEntrySubmitShouldReturnExistingProcessingResultForDuplicateRequest() {
        SeckillServiceImpl service = newAsyncEntryService();
        stubMetadata(activeActivity(), sku());
        stubAsyncEntryEnabled();
        when(entryGuard.acquireRequest("client-r1"))
                .thenReturn(new SeckillEntryGuard.RequestDecision(SeckillEntryGuard.RequestOutcome.DUPLICATE));
        when(repository.result("client-r1"))
                .thenReturn(new SeckillResult("client-r1", "PROCESSING", null, "Processing"));

        UserContext.set(new UserInfo(101L, "u101"));
        SeckillSubmitResponse response = service.submit(1L, 1001L, "client-r1");

        assertThat(response.status()).isEqualTo("PROCESSING");
        assertThat(response.message()).isEqualTo("Processing");
        verify(entryGuard, never()).acquireBuyer(anyLong(), anyLong(), anyLong(), anyString(), any());
        verify(repository, never()).selectBucket(anyLong(), anyLong());
    }

    @Test
    void asyncEntrySubmitShouldReturnProcessingForSameBuyerRequestWithoutNewSnapshot() {
        SeckillServiceImpl service = newAsyncEntryService();
        SeckillActivity activity = activeActivity();
        stubMetadata(activity, sku());
        stubAsyncEntryEnabled();
        when(entryGuard.acquireRequest("client-r1"))
                .thenReturn(new SeckillEntryGuard.RequestDecision(SeckillEntryGuard.RequestOutcome.ACQUIRED));
        when(entryGuard.acquireBuyer(eq(1L), eq(1001L), eq(101L), eq("client-r1"), eq(activity.endAt())))
                .thenReturn(new SeckillEntryGuard.BuyerDecision(SeckillEntryGuard.BuyerOutcome.SAME_REQUEST, "client-r1"));
        when(repository.result("client-r1")).thenReturn(null);

        UserContext.set(new UserInfo(101L, "u101"));
        SeckillSubmitResponse response = service.submit(1L, 1001L, "client-r1");

        assertThat(response.status()).isEqualTo("PROCESSING");
        verify(repository, never()).selectBucket(anyLong(), anyLong());
        verify(repository, never()).registerBucketSnapshot(anyString(), anyLong(), anyLong(), anyLong(), anyLong(), eq(1), any());
    }

    @Test
    void asyncEntrySubmitShouldFailDuplicateBuyerBeforeSnapshot() {
        SeckillServiceImpl service = newAsyncEntryService();
        SeckillActivity activity = activeActivity();
        stubMetadata(activity, sku());
        stubAsyncEntryEnabled();
        when(entryGuard.acquireRequest("client-r1"))
                .thenReturn(new SeckillEntryGuard.RequestDecision(SeckillEntryGuard.RequestOutcome.ACQUIRED));
        when(entryGuard.acquireBuyer(eq(1L), eq(1001L), eq(101L), eq("client-r1"), eq(activity.endAt())))
                .thenReturn(new SeckillEntryGuard.BuyerDecision(SeckillEntryGuard.BuyerOutcome.DUPLICATE_BUYER, "other-r1"));

        UserContext.set(new UserInfo(101L, "u101"));
        SeckillSubmitResponse response = service.submit(1L, 1001L, "client-r1");

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.message()).isEqualTo("Duplicate purchase");
        verify(repository, never()).selectBucket(anyLong(), anyLong());
        verify(repository).saveResult(new SeckillResult("client-r1", "FAILED", null, "Duplicate purchase"));
    }

    @Test
    void asyncEntrySubmitShouldReleaseBuyerAndSkipBucketWorkWhenStockCacheSoldOut() {
        SeckillServiceImpl service = newAsyncEntryService();
        SeckillActivity activity = activeActivity();
        stubMetadata(activity, sku());
        stubAsyncEntryEnabled();
        when(entryGuard.acquireRequest("client-r1"))
                .thenReturn(new SeckillEntryGuard.RequestDecision(SeckillEntryGuard.RequestOutcome.ACQUIRED));
        when(entryGuard.acquireBuyer(eq(1L), eq(1001L), eq(101L), eq("client-r1"), eq(activity.endAt())))
                .thenReturn(new SeckillEntryGuard.BuyerDecision(SeckillEntryGuard.BuyerOutcome.ACQUIRED, null));
        when(stockCache.isSoldOut(1L, 1001L)).thenReturn(true);

        UserContext.set(new UserInfo(101L, "u101"));
        SeckillSubmitResponse response = service.submit(1L, 1001L, "client-r1");

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.message()).isEqualTo("Stock not enough");
        verify(entryGuard).releaseBuyer(1L, 1001L, 101L, "client-r1");
        verify(repository, never()).selectBucket(anyLong(), anyLong());
        verify(repository, never()).registerBucketSnapshot(anyString(), anyLong(), anyLong(), anyLong(), anyLong(), eq(1), any());
        verify(repository, never()).recordBucketDeductionFact(anyString(), anyLong(), anyLong(), any(), eq(1));
        verify(repository).saveResult(new SeckillResult("client-r1", "FAILED", null, "Stock not enough"));
    }

    @Test
    void asyncEntrySubmitShouldReleaseBuyerWhenBucketStockNotEnough() {
        SeckillServiceImpl service = newAsyncEntryService();
        SeckillActivity activity = activeActivity();
        stubMetadata(activity, sku());
        stubAsyncEntryEnabled();
        when(entryGuard.acquireRequest("client-r1"))
                .thenReturn(new SeckillEntryGuard.RequestDecision(SeckillEntryGuard.RequestOutcome.ACQUIRED));
        when(entryGuard.acquireBuyer(eq(1L), eq(1001L), eq(101L), eq("client-r1"), eq(activity.endAt())))
                .thenReturn(new SeckillEntryGuard.BuyerDecision(SeckillEntryGuard.BuyerOutcome.ACQUIRED, null));
        when(repository.selectBucket(1L, 1001L)).thenThrow(new SeckillStockNotEnoughException());

        UserContext.set(new UserInfo(101L, "u101"));
        SeckillSubmitResponse response = service.submit(1L, 1001L, "client-r1");

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.message()).isEqualTo("Stock not enough");
        verify(entryGuard).releaseBuyer(1L, 1001L, 101L, "client-r1");
        verify(repository, never()).registerBucketSnapshot(anyString(), anyLong(), anyLong(), anyLong(), anyLong(), eq(1), any());
        verify(repository).saveResult(new SeckillResult("client-r1", "FAILED", null, "Stock not enough"));
    }

    @Test
    void asyncEntrySubmitShouldReturnExistingResultWhenSnapshotReportsRequestDuplicate() {
        SeckillServiceImpl service = newAsyncEntryService();
        SeckillActivity activity = activeActivity();
        SeckillBucketService.SelectedBucket selectedBucket = bucket();
        stubMetadata(activity, sku());
        stubAsyncEntryEnabled();
        when(entryGuard.acquireRequest("client-r1"))
                .thenReturn(new SeckillEntryGuard.RequestDecision(SeckillEntryGuard.RequestOutcome.ACQUIRED));
        when(entryGuard.acquireBuyer(eq(1L), eq(1001L), eq(101L), eq("client-r1"), eq(activity.endAt())))
                .thenReturn(new SeckillEntryGuard.BuyerDecision(SeckillEntryGuard.BuyerOutcome.ACQUIRED, null));
        when(repository.selectBucket(1L, 1001L)).thenReturn(selectedBucket);
        when(repository.registerBucketSnapshot("client-r1", 10L, 1L, 1001L, 101L, 1, selectedBucket))
                .thenReturn(new SeckillRepository.SnapshotRegistration(
                        SeckillRepository.SnapshotRegistrationOutcome.REQUEST_DUPLICATE,
                        null));
        when(repository.result("client-r1"))
                .thenReturn(new SeckillResult("client-r1", "PROCESSING", null, "Processing"));

        UserContext.set(new UserInfo(101L, "u101"));
        SeckillSubmitResponse response = service.submit(1L, 1001L, "client-r1");

        assertThat(response.status()).isEqualTo("PROCESSING");
        verify(repository, never()).recordBucketDeductionFact(anyString(), anyLong(), anyLong(), any(), eq(1));
        verify(entryGuard, never()).releaseBuyer(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void asyncEntrySubmitShouldReleaseBuyerWhenSnapshotReportsActiveDuplicate() {
        SeckillServiceImpl service = newAsyncEntryService();
        SeckillActivity activity = activeActivity();
        SeckillBucketService.SelectedBucket selectedBucket = bucket();
        stubMetadata(activity, sku());
        stubAsyncEntryEnabled();
        when(entryGuard.acquireRequest("client-r1"))
                .thenReturn(new SeckillEntryGuard.RequestDecision(SeckillEntryGuard.RequestOutcome.ACQUIRED));
        when(entryGuard.acquireBuyer(eq(1L), eq(1001L), eq(101L), eq("client-r1"), eq(activity.endAt())))
                .thenReturn(new SeckillEntryGuard.BuyerDecision(SeckillEntryGuard.BuyerOutcome.ACQUIRED, null));
        when(repository.selectBucket(1L, 1001L)).thenReturn(selectedBucket);
        when(repository.registerBucketSnapshot("client-r1", 10L, 1L, 1001L, 101L, 1, selectedBucket))
                .thenReturn(new SeckillRepository.SnapshotRegistration(
                        SeckillRepository.SnapshotRegistrationOutcome.ACTIVE_DUPLICATE,
                        null));

        UserContext.set(new UserInfo(101L, "u101"));
        SeckillSubmitResponse response = service.submit(1L, 1001L, "client-r1");

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.message()).isEqualTo("Duplicate purchase");
        verify(entryGuard).releaseBuyer(1L, 1001L, 101L, "client-r1");
        verify(repository, never()).recordBucketDeductionFact(anyString(), anyLong(), anyLong(), any(), eq(1));
        verify(repository).saveResult(new SeckillResult("client-r1", "FAILED", null, "Duplicate purchase"));
    }

    @Test
    void asyncEntrySubmitShouldReleaseBuyerAndMarkSnapshotFailedWhenDeductionFactStockNotEnough() {
        SeckillServiceImpl service = newAsyncEntryService();
        SeckillActivity activity = activeActivity();
        SeckillBucketService.SelectedBucket selectedBucket = bucket();
        stubMetadata(activity, sku());
        stubAsyncEntryEnabled();
        when(entryGuard.acquireRequest("client-r1"))
                .thenReturn(new SeckillEntryGuard.RequestDecision(SeckillEntryGuard.RequestOutcome.ACQUIRED));
        when(entryGuard.acquireBuyer(eq(1L), eq(1001L), eq(101L), eq("client-r1"), eq(activity.endAt())))
                .thenReturn(new SeckillEntryGuard.BuyerDecision(SeckillEntryGuard.BuyerOutcome.ACQUIRED, null));
        when(repository.selectBucket(1L, 1001L)).thenReturn(selectedBucket);
        when(repository.registerBucketSnapshot("client-r1", 10L, 1L, 1001L, 101L, 1, selectedBucket))
                .thenReturn(snapshotCreated("client-r1"));
        when(repository.recordBucketDeductionFact("client-r1", 1L, 1001L, selectedBucket, 1))
                .thenThrow(new SeckillStockNotEnoughException());

        UserContext.set(new UserInfo(101L, "u101"));
        SeckillSubmitResponse response = service.submit(1L, 1001L, "client-r1");

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.message()).isEqualTo("Stock not enough");
        verify(entryGuard).releaseBuyer(1L, 1001L, 101L, "client-r1");
        verify(repository).markRegisteredSnapshotFailed("client-r1", "Stock not enough");
        verify(repository).saveResult(new SeckillResult("client-r1", "FAILED", null, "Stock not enough"));
    }

    @Test
    void asyncEntrySubmitShouldReleaseBuyerAndMarkSnapshotFailedWhenDeductionFactDuplicate() {
        SeckillServiceImpl service = newAsyncEntryService();
        SeckillActivity activity = activeActivity();
        SeckillBucketService.SelectedBucket selectedBucket = bucket();
        stubMetadata(activity, sku());
        stubAsyncEntryEnabled();
        when(entryGuard.acquireRequest("client-r1"))
                .thenReturn(new SeckillEntryGuard.RequestDecision(SeckillEntryGuard.RequestOutcome.ACQUIRED));
        when(entryGuard.acquireBuyer(eq(1L), eq(1001L), eq(101L), eq("client-r1"), eq(activity.endAt())))
                .thenReturn(new SeckillEntryGuard.BuyerDecision(SeckillEntryGuard.BuyerOutcome.ACQUIRED, null));
        when(repository.selectBucket(1L, 1001L)).thenReturn(selectedBucket);
        when(repository.registerBucketSnapshot("client-r1", 10L, 1L, 1001L, 101L, 1, selectedBucket))
                .thenReturn(snapshotCreated("client-r1"));
        when(repository.recordBucketDeductionFact("client-r1", 1L, 1001L, selectedBucket, 1))
                .thenReturn(StockDeductionResult.duplicate());

        UserContext.set(new UserInfo(101L, "u101"));
        SeckillSubmitResponse response = service.submit(1L, 1001L, "client-r1");

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.message()).isEqualTo("Duplicate purchase");
        verify(entryGuard).releaseBuyer(1L, 1001L, 101L, "client-r1");
        verify(repository).markRegisteredSnapshotFailed("client-r1", "Duplicate purchase");
        verify(repository).saveResult(new SeckillResult("client-r1", "FAILED", null, "Duplicate purchase"));
    }

    @Test
    void asyncEntrySubmitShouldUseHotspotSentinelResourceForConfiguredHotspotSubmit() {
        SeckillServiceImpl service = newAsyncEntryService();
        SeckillActivity activity = activeActivity();
        SeckillBucketService.SelectedBucket selectedBucket = bucket();
        when(hotspotGuard.isHotspot(1L, 1001L)).thenReturn(true);
        stubMetadata(activity, sku());
        stubAsyncEntryEnabled();
        when(entryGuard.acquireRequest("client-r1"))
                .thenReturn(new SeckillEntryGuard.RequestDecision(SeckillEntryGuard.RequestOutcome.ACQUIRED));
        when(entryGuard.acquireBuyer(eq(1L), eq(1001L), eq(101L), eq("client-r1"), eq(activity.endAt())))
                .thenReturn(new SeckillEntryGuard.BuyerDecision(SeckillEntryGuard.BuyerOutcome.ACQUIRED, null));
        when(repository.selectBucket(1L, 1001L)).thenReturn(selectedBucket);
        when(repository.registerBucketSnapshot("client-r1", 10L, 1L, 1001L, 101L, 1, selectedBucket))
                .thenReturn(snapshotCreated("client-r1"));
        when(repository.recordBucketDeductionFact("client-r1", 1L, 1001L, selectedBucket, 1))
                .thenReturn(StockDeductionResult.success(null));

        UserContext.set(new UserInfo(101L, "u101"));
        SeckillSubmitResponse response = service.submit(1L, 1001L, "client-r1");

        assertThat(response.status()).isEqualTo("PROCESSING");
        verify(sentinelGuard).checkSubmit(true);
        verify(hotspotGuard).acquire(1L, 1001L);
    }

    @Test
    void shouldPrewarmMetadataAndTairStringStockCache() {
        SeckillServiceImpl service = newService(60_000);
        SeckillActivity activity = activeActivity();
        SeckillSku sku = sku();
        StockVersion stockVersion = new StockVersion(50, 7L);
        when(repository.requireActivity(1L)).thenReturn(activity);
        when(repository.requireSku(1L, 1001L)).thenReturn(sku);
        when(repository.stockVersion(10L, 1L, 1001L)).thenReturn(stockVersion);

        service.prewarm(1L, 1001L);

        verify(repository).requireActivity(1L);
        verify(repository).requireSku(1L, 1001L);
        verify(stockCache).refresh(1L, 1001L, stockVersion);
    }

    private void stubMetadata(SeckillActivity activity, SeckillSku sku) {
        when(repository.requireActivity(1L)).thenReturn(activity);
        when(repository.requireSku(1L, 1001L)).thenReturn(sku);
    }

    private void stubAsyncEntryEnabled() {
        when(repository.isBucketModeEnabled()).thenReturn(true);
        when(entryGuard.enabled()).thenReturn(true);
    }

    private SeckillRepository.SnapshotRegistration snapshotCreated(String requestId) {
        return new SeckillRepository.SnapshotRegistration(
                SeckillRepository.SnapshotRegistrationOutcome.CREATED,
                new SeckillRepository.StockSnapshot(requestId, 1L, 1001L, 101L, 1, "REGISTERED"));
    }

    private SeckillActivity activeActivity() {
        return new SeckillActivity(1L, "flash", Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
    }

    private SeckillSku sku() {
        return new SeckillSku(10L, 1L, 1001L, "phone", BigDecimal.valueOf(99), 50);
    }

    private SeckillBucketService.SelectedBucket bucket() {
        return new SeckillBucketService.SelectedBucket(99L, 3, 3L, 7L, 1L);
    }

    private SeckillServiceImpl newService(long metadataCacheTtlMillis) {
        SeckillProperties properties = new SeckillProperties();
        properties.getLock().setEnabled(false);
        properties.setMetadataCacheTtlMillis(metadataCacheTtlMillis);
        return newService(properties, null);
    }

    private SeckillServiceImpl newAsyncEntryService() {
        return newAsyncEntryService(0);
    }

    private SeckillServiceImpl newAsyncEntryService(long metadataCacheTtlMillis) {
        SeckillProperties properties = new SeckillProperties();
        properties.getLock().setEnabled(false);
        properties.getEntryGuard().setEnabled(true);
        properties.setMetadataCacheTtlMillis(metadataCacheTtlMillis);
        return newService(properties, entryGuard);
    }

    private SeckillServiceImpl newService(SeckillProperties properties,
                                          SeckillEntryGuard entryGuard) {
        return new SeckillServiceImpl(
                repository,
                stockCache,
                sentinelGuard,
                hotspotGuard,
                emptyRedissonProvider(),
                properties,
                new SimpleMeterRegistry(),
                entryGuard
        );
    }

    private ObjectProvider<RedissonClient> emptyRedissonProvider() {
        return new ObjectProvider<>() {
            @Override
            public RedissonClient getObject(Object... args) {
                return null;
            }

            @Override
            public RedissonClient getIfAvailable() {
                return null;
            }

            @Override
            public RedissonClient getIfUnique() {
                return null;
            }

            @Override
            public RedissonClient getObject() {
                return null;
            }
        };
    }

}

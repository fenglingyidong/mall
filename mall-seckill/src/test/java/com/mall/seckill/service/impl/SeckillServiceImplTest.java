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
import com.mall.seckill.pojo.vo.StockVersion;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    private SeckillEntryFactWriter entryFactWriter;

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
    void asyncEntrySubmitShouldRecordEntryFactWithoutOrderOutbox() {
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
        when(entryFactWriter.recordAcceptedEntry(any()))
                .thenReturn(entryCreated("client-r1", selectedBucket));

        UserContext.set(new UserInfo(101L, "u101"));
        SeckillSubmitResponse response = service.submit(1L, 1001L, "client-r1");

        assertThat(response.requestId()).isEqualTo("client-r1");
        assertThat(response.status()).isEqualTo("PROCESSING");
        assertThat(response.message()).isEqualTo("Processing");
        ArgumentCaptor<SeckillEntryFactWriter.EntryFactCommand> commandCaptor =
                ArgumentCaptor.forClass(SeckillEntryFactWriter.EntryFactCommand.class);
        verify(entryFactWriter).recordAcceptedEntry(commandCaptor.capture());
        SeckillEntryFactWriter.EntryFactCommand command = commandCaptor.getValue();
        assertThat(command.requestId()).isEqualTo("client-r1");
        assertThat(command.stockId()).isEqualTo(10L);
        assertThat(command.activityId()).isEqualTo(1L);
        assertThat(command.skuId()).isEqualTo(1001L);
        assertThat(command.userId()).isEqualTo(101L);
        assertThat(command.quantity()).isEqualTo(1);
        assertThat(command.selectedBucket()).isEqualTo(selectedBucket);
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
        when(entryFactWriter.recordAcceptedEntry(any()))
                .thenAnswer(invocation -> {
                    SeckillEntryFactWriter.EntryFactCommand command = invocation.getArgument(0);
                    return entryCreated(command.requestId(), selectedBucket);
                });

        UserContext.set(new UserInfo(101L, "u101"));
        SeckillSubmitResponse first = service.submit(1L, 1001L, null);
        UserContext.set(new UserInfo(102L, "u102"));
        SeckillSubmitResponse second = service.submit(1L, 1001L, null);

        assertThat(first.status()).isEqualTo("PROCESSING");
        assertThat(second.status()).isEqualTo("PROCESSING");
        verify(repository, times(1)).requireActivity(1L);
        verify(repository, times(1)).requireSku(1L, 1001L);
        verify(entryFactWriter, times(2)).recordAcceptedEntry(any());
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
        when(entryFactWriter.recordAcceptedEntry(any()))
                .thenReturn(entryCreated("client-r1", selectedBucket));

        UserContext.set(new UserInfo(101L, "u101"));
        SeckillSubmitResponse response = service.submit(1L, 1001L, " client-r1 ");

        assertThat(response.requestId()).isEqualTo("client-r1");
        verify(entryGuard).acquireRequest("client-r1");
        ArgumentCaptor<SeckillEntryFactWriter.EntryFactCommand> commandCaptor =
                ArgumentCaptor.forClass(SeckillEntryFactWriter.EntryFactCommand.class);
        verify(entryFactWriter).recordAcceptedEntry(commandCaptor.capture());
        assertThat(commandCaptor.getValue().requestId()).isEqualTo("client-r1");
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
        verify(entryFactWriter, never()).recordAcceptedEntry(any());
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
        verify(entryFactWriter, never()).recordAcceptedEntry(any());
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
        verify(entryFactWriter, never()).recordAcceptedEntry(any());
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
        verify(entryFactWriter, never()).recordAcceptedEntry(any());
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
        verify(entryFactWriter, never()).recordAcceptedEntry(any());
        verify(repository).saveResult(new SeckillResult("client-r1", "FAILED", null, "Stock not enough"));
    }

    @Test
    void asyncEntrySubmitShouldReturnExistingResultWhenEntryFactReportsRequestDuplicate() {
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
        when(entryFactWriter.recordAcceptedEntry(any()))
                .thenReturn(entryResult(SeckillEntryFactWriter.EntryFactOutcome.REQUEST_DUPLICATE, "client-r1", selectedBucket));
        when(repository.result("client-r1"))
                .thenReturn(new SeckillResult("client-r1", "PROCESSING", null, "Processing"));

        UserContext.set(new UserInfo(101L, "u101"));
        SeckillSubmitResponse response = service.submit(1L, 1001L, "client-r1");

        assertThat(response.status()).isEqualTo("PROCESSING");
        verify(entryGuard, never()).releaseBuyer(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void asyncEntrySubmitShouldReleaseBuyerWhenEntryFactReportsActiveDuplicate() {
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
        when(entryFactWriter.recordAcceptedEntry(any()))
                .thenReturn(entryResult(SeckillEntryFactWriter.EntryFactOutcome.ACTIVE_DUPLICATE, "client-r1", selectedBucket));

        UserContext.set(new UserInfo(101L, "u101"));
        SeckillSubmitResponse response = service.submit(1L, 1001L, "client-r1");

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.message()).isEqualTo("Duplicate purchase");
        verify(entryGuard).releaseBuyer(1L, 1001L, 101L, "client-r1");
        verify(repository).saveResult(new SeckillResult("client-r1", "FAILED", null, "Duplicate purchase"));
    }

    @Test
    void asyncEntrySubmitShouldReleaseBuyerWhenEntryFactReportsStockNotEnough() {
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
        when(entryFactWriter.recordAcceptedEntry(any()))
                .thenReturn(entryResult(SeckillEntryFactWriter.EntryFactOutcome.STOCK_NOT_ENOUGH, "client-r1", selectedBucket));

        UserContext.set(new UserInfo(101L, "u101"));
        SeckillSubmitResponse response = service.submit(1L, 1001L, "client-r1");

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.message()).isEqualTo("Stock not enough");
        verify(entryGuard).releaseBuyer(1L, 1001L, 101L, "client-r1");
        verify(repository, never()).markRegisteredSnapshotFailed(anyString(), anyString());
        verify(repository).saveResult(new SeckillResult("client-r1", "FAILED", null, "Stock not enough"));
    }

    @Test
    void asyncEntrySubmitShouldReleaseBuyerWhenEntryFactReportsDeductDuplicate() {
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
        when(entryFactWriter.recordAcceptedEntry(any()))
                .thenReturn(entryResult(SeckillEntryFactWriter.EntryFactOutcome.DEDUCT_DUPLICATE, "client-r1", selectedBucket));

        UserContext.set(new UserInfo(101L, "u101"));
        SeckillSubmitResponse response = service.submit(1L, 1001L, "client-r1");

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.message()).isEqualTo("Duplicate purchase");
        verify(entryGuard).releaseBuyer(1L, 1001L, 101L, "client-r1");
        verify(repository, never()).markRegisteredSnapshotFailed(anyString(), anyString());
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
        when(entryFactWriter.recordAcceptedEntry(any()))
                .thenReturn(entryCreated("client-r1", selectedBucket));

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

    private SeckillEntryFactWriter.EntryFactResult entryCreated(String requestId,
                                                                SeckillBucketService.SelectedBucket selectedBucket) {
        return entryResult(SeckillEntryFactWriter.EntryFactOutcome.CREATED, requestId, selectedBucket);
    }

    private SeckillEntryFactWriter.EntryFactResult entryResult(SeckillEntryFactWriter.EntryFactOutcome outcome,
                                                               String requestId,
                                                               SeckillBucketService.SelectedBucket selectedBucket) {
        return new SeckillEntryFactWriter.EntryFactResult(
                outcome,
                null,
                new SeckillEntryFactWriter.EntryStockSnapshot(requestId, 1L, 1001L, 101L, 1, "REGISTERED"),
                selectedBucket,
                null);
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
                entryFactWriter,
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

package com.mall.seckill.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.context.UserContext;
import com.mall.common.context.UserInfo;
import com.mall.message.ReliableMessagePublisher;
import com.mall.seckill.cache.SeckillStockCache;
import com.mall.seckill.config.SeckillProperties;
import com.mall.seckill.mapper.ReservationGuardRepository;
import com.mall.seckill.mapper.SeckillRepository;
import com.mall.seckill.mapper.SeckillStockNotEnoughException;
import com.mall.seckill.pojo.entity.SeckillReservationGuardEntity;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.sql.SQLTransientConnectionException;
import java.time.Instant;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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
    private ReliableMessagePublisher messagePublisher;

    @Mock
    private ReservationGuardRepository guardRepository;

    @AfterEach
    void tearDown() {
        UserContext.clear();
}
    @Test
    void shouldCacheMetadataAndSkipProcessingResultOnAcceptedSubmit() {
        SeckillServiceImpl service = newService(60_000);
        SeckillActivity activity = new SeckillActivity(1L, "flash", Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        SeckillSku sku = new SeckillSku(10L, 1L, 1001L, "phone", BigDecimal.valueOf(99), 50);
        StockVersion stockVersion = new StockVersion(49, 1L);
        when(repository.requireActivity(1L)).thenReturn(activity);
        when(repository.requireSku(1L, 1001L)).thenReturn(sku);
        when(repository.recordDeduction(anyString(), eq(10L), eq(1L), eq(1001L), anyLong(), eq(1), any(Consumer.class)))
                .thenAnswer(invocation -> {
                    invocation.<Consumer<Long>>getArgument(6).accept(3L);
                    return StockDeductionResult.success(stockVersion);
                });

        UserContext.set(new UserInfo(101L, "u101"));
        SeckillSubmitResponse first = service.submit(1L, 1001L);
        UserContext.set(new UserInfo(102L, "u102"));
        SeckillSubmitResponse second = service.submit(1L, 1001L);

        assertThat(first.status()).isEqualTo("ACCEPTED");
        assertThat(second.status()).isEqualTo("ACCEPTED");
        verify(repository, times(1)).requireActivity(1L);
        verify(repository, times(1)).requireSku(1L, 1001L);
        verify(repository, never()).hasActiveDeduction(anyLong(), anyLong());
        verify(repository, times(2)).recordDeduction(anyString(), eq(10L), eq(1L), eq(1001L), anyLong(), eq(1), any(Consumer.class));
        verify(stockCache, times(2)).refresh(1L, 1001L, stockVersion);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(messagePublisher, times(2)).enqueueSeckillOrderCreate(anyString(), payloadCaptor.capture(), eq(3L));
        assertThat(payloadCaptor.getAllValues()).allSatisfy(payload -> assertThat(payload).contains("\"bucketShardKey\":3"));
        verify(repository, never()).saveResult(any());
    }

    @Test
    void shouldSkipStockCacheRefreshWhenDeductionDoesNotReturnStockVersion() {
        SeckillServiceImpl service = newService(60_000);
        SeckillActivity activity = new SeckillActivity(1L, "flash", Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        SeckillSku sku = new SeckillSku(10L, 1L, 1001L, "phone", BigDecimal.valueOf(99), 50);
        when(repository.requireActivity(1L)).thenReturn(activity);
        when(repository.requireSku(1L, 1001L)).thenReturn(sku);
        when(repository.recordDeduction(anyString(), eq(10L), eq(1L), eq(1001L), eq(101L), eq(1), any(Consumer.class)))
                .thenAnswer(invocation -> {
                    invocation.<Consumer<Long>>getArgument(6).accept(3L);
                    return StockDeductionResult.success(null);
                });

        UserContext.set(new UserInfo(101L, "u101"));
        SeckillSubmitResponse response = service.submit(1L, 1001L);

        assertThat(response.status()).isEqualTo("ACCEPTED");
        verify(stockCache, never()).refresh(anyLong(), anyLong(), any());
        verify(messagePublisher).enqueueSeckillOrderCreate(anyString(), anyString(), eq(3L));
    }

    @Test
    void shouldKeepDuplicatePurchaseGuardInsideRecordDeduction() {
        SeckillServiceImpl service = newService(60_000);
        SeckillActivity activity = new SeckillActivity(1L, "flash", Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        SeckillSku sku = new SeckillSku(10L, 1L, 1001L, "phone", BigDecimal.valueOf(99), 50);
        when(repository.requireActivity(1L)).thenReturn(activity);
        when(repository.requireSku(1L, 1001L)).thenReturn(sku);
        when(repository.recordDeduction(anyString(), eq(10L), eq(1L), eq(1001L), eq(101L), eq(1), any(Consumer.class)))
                .thenReturn(StockDeductionResult.duplicate());

        UserContext.set(new UserInfo(101L, "u101"));
        SeckillSubmitResponse response = service.submit(1L, 1001L);

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.message()).isEqualTo("Duplicate purchase");
        ArgumentCaptor<SeckillResult> resultCaptor = ArgumentCaptor.forClass(SeckillResult.class);
        verify(repository).saveResult(resultCaptor.capture());
        assertThat(resultCaptor.getValue().status()).isEqualTo("FAILED");
        assertThat(resultCaptor.getValue().message()).isEqualTo("Duplicate purchase");
        verify(repository, never()).hasActiveDeduction(anyLong(), anyLong());
        verify(messagePublisher, never()).enqueueSeckillOrderCreate(anyString(), anyString(), eq(3L));
        verify(stockCache, never()).refresh(anyLong(), anyLong(), any());
    }

    @Test
    void shouldFailFastWhenStockCacheSaysSoldOut() {
        SeckillServiceImpl service = newService(60_000);
        SeckillActivity activity = new SeckillActivity(1L, "flash", Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        SeckillSku sku = new SeckillSku(10L, 1L, 1001L, "phone", BigDecimal.valueOf(99), 50);
        when(repository.requireActivity(1L)).thenReturn(activity);
        when(repository.requireSku(1L, 1001L)).thenReturn(sku);
        when(stockCache.isSoldOut(1L, 1001L)).thenReturn(true);

        UserContext.set(new UserInfo(101L, "u101"));

        SeckillSubmitResponse response = service.submit(1L, 1001L);

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.message()).isEqualTo("Stock not enough");
        verify(repository, never()).recordDeduction(anyString(), anyLong(), anyLong(), anyLong(), anyLong(), eq(1), any(Consumer.class));
        verify(messagePublisher, never()).enqueueSeckillOrderCreate(anyString(), anyString(), eq(3L));
    }

    @Test
    void shouldReturnStockNotEnoughWhenOceanBaseDeductionFails() {
        SeckillServiceImpl service = newService(60_000);
        SeckillActivity activity = new SeckillActivity(1L, "flash", Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        SeckillSku sku = new SeckillSku(10L, 1L, 1001L, "phone", BigDecimal.valueOf(99), 50);
        when(repository.requireActivity(1L)).thenReturn(activity);
        when(repository.requireSku(1L, 1001L)).thenReturn(sku);
        when(repository.recordDeduction(anyString(), eq(10L), eq(1L), eq(1001L), eq(101L), eq(1), any(Consumer.class)))
                .thenThrow(new SeckillStockNotEnoughException());

        UserContext.set(new UserInfo(101L, "u101"));

        SeckillSubmitResponse response = service.submit(1L, 1001L);

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.message()).isEqualTo("Stock not enough");
        verify(stockCache, never()).refresh(anyLong(), anyLong(), any());
        verify(messagePublisher, never()).enqueueSeckillOrderCreate(anyString(), anyString(), eq(3L));
    }

    @Test
    void shouldRetryRecordDeductionWhenDatabaseDeadlockIsTransient() {
        SeckillServiceImpl service = newService(60_000);
        SeckillActivity activity = new SeckillActivity(1L, "flash", Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        SeckillSku sku = new SeckillSku(10L, 1L, 1001L, "phone", BigDecimal.valueOf(99), 50);
        StockVersion stockVersion = new StockVersion(49, 1L);
        when(repository.requireActivity(1L)).thenReturn(activity);
        when(repository.requireSku(1L, 1001L)).thenReturn(sku);
        when(repository.recordDeduction(anyString(), eq(10L), eq(1L), eq(1001L), eq(101L), eq(1), any(Consumer.class)))
                .thenThrow(new TransientDataAccessResourceException(
                        "deadlock",
                        new SQLTransientConnectionException("Deadlock found when trying to get lock")))
                .thenAnswer(invocation -> {
                    invocation.<Consumer<Long>>getArgument(6).accept(3L);
                    return StockDeductionResult.success(stockVersion);
                });

        UserContext.set(new UserInfo(101L, "u101"));

        SeckillSubmitResponse response = service.submit(1L, 1001L);

        assertThat(response.status()).isEqualTo("ACCEPTED");
        ArgumentCaptor<String> requestCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository, times(2)).recordDeduction(
                requestCaptor.capture(),
                eq(10L),
                eq(1L),
                eq(1001L),
                eq(101L),
                eq(1),
                any(Consumer.class));
        assertThat(requestCaptor.getAllValues()).containsOnly(requestCaptor.getAllValues().getFirst());
        verify(messagePublisher).enqueueSeckillOrderCreate(anyString(), anyString(), eq(3L));
        verify(stockCache).refresh(1L, 1001L, stockVersion);
        verify(repository, never()).saveResult(any());
    }

    @Test
    void guardedSubmitShouldAttachBucketBeforeDeduction() {
        SeckillServiceImpl service = newGuardedService();
        SeckillActivity activity = new SeckillActivity(1L, "flash", Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        SeckillSku sku = new SeckillSku(10L, 1L, 1001L, "phone", BigDecimal.valueOf(99), 50);
        StockVersion stockVersion = new StockVersion(49, 1L);
        SeckillBucketService.SelectedBucket selectedBucket = new SeckillBucketService.SelectedBucket(99L, 3, 3L, 7L, 1L);
        SeckillReservationGuardEntity guard = new SeckillReservationGuardEntity();
        guard.setReservationId("r1");
        guard.setRequestId("r1");
        guard.setStatus(ReservationGuardRepository.STATUS_PROCESSING);
        when(repository.requireActivity(1L)).thenReturn(activity);
        when(repository.requireSku(1L, 1001L)).thenReturn(sku);
        when(repository.isBucketModeEnabled()).thenReturn(true);
        when(guardRepository.createOrLoad(anyString(), any(ReservationGuardRepository.ReservationDraft.class)))
                .thenReturn(new ReservationGuardRepository.CreateGuardResult(
                        ReservationGuardRepository.GuardCreateOutcome.CREATED,
                        guard));
        when(repository.selectBucket(1L, 1001L)).thenReturn(selectedBucket);
        when(guardRepository.attachBucket(anyString(), eq(selectedBucket))).thenReturn(true);
        when(repository.recordDeduction(anyString(), eq(10L), eq(1L), eq(1001L), eq(101L), eq(1), eq(selectedBucket), any(Consumer.class)))
                .thenAnswer(invocation -> {
                    invocation.<Consumer<Long>>getArgument(7).accept(3L);
                    return StockDeductionResult.success(stockVersion);
                });

        UserContext.set(new UserInfo(101L, "u101"));
        SeckillSubmitResponse response = service.submit(1L, 1001L);

        assertThat(response.status()).isEqualTo("ACCEPTED");
        InOrder order = inOrder(guardRepository, repository);
        order.verify(guardRepository).createOrLoad(anyString(), any(ReservationGuardRepository.ReservationDraft.class));
        order.verify(repository).selectBucket(1L, 1001L);
        order.verify(guardRepository).attachBucket(anyString(), eq(selectedBucket));
        order.verify(repository).recordDeduction(anyString(), eq(10L), eq(1L), eq(1001L), eq(101L), eq(1), eq(selectedBucket), any(Consumer.class));
        verify(guardRepository).markDeducted(anyString());
        verify(messagePublisher).enqueueSeckillOrderCreate(anyString(), anyString(), eq(3L));
    }

    @Test
    void shouldNotReleaseDeductionWhenOutboxEnqueueFails() {
        SeckillServiceImpl service = newService(60_000);
        SeckillActivity activity = new SeckillActivity(1L, "flash", Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        SeckillSku sku = new SeckillSku(10L, 1L, 1001L, "phone", BigDecimal.valueOf(99), 50);
        StockVersion deductedVersion = new StockVersion(49, 1L);
        when(repository.requireActivity(1L)).thenReturn(activity);
        when(repository.requireSku(1L, 1001L)).thenReturn(sku);
        when(repository.recordDeduction(anyString(), eq(10L), eq(1L), eq(1001L), eq(101L), eq(1), any(Consumer.class)))
                .thenAnswer(invocation -> {
                    invocation.<Consumer<Long>>getArgument(6).accept(3L);
                    return StockDeductionResult.success(deductedVersion);
                });
        doThrow(new RuntimeException("outbox failed"))
                .when(messagePublisher).enqueueSeckillOrderCreate(anyString(), anyString(), eq(3L));

        UserContext.set(new UserInfo(101L, "u101"));

        assertThatThrownBy(() -> service.submit(1L, 1001L))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("outbox failed");

        verify(repository, never()).releaseDeduction(anyString(), anyString());
        verify(stockCache, never()).refresh(1L, 1001L, deductedVersion);
    }

    @Test
    void shouldUseHotspotSentinelResourceForConfiguredHotspotSubmit() {
        SeckillServiceImpl service = newService(60_000);
        SeckillActivity activity = new SeckillActivity(1L, "flash", Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        SeckillSku sku = new SeckillSku(10L, 1L, 1001L, "phone", BigDecimal.valueOf(99), 50);
        StockVersion stockVersion = new StockVersion(49, 1L);
        when(hotspotGuard.isHotspot(1L, 1001L)).thenReturn(true);
        when(repository.requireActivity(1L)).thenReturn(activity);
        when(repository.requireSku(1L, 1001L)).thenReturn(sku);
        when(repository.recordDeduction(anyString(), eq(10L), eq(1L), eq(1001L), eq(101L), eq(1), any(Consumer.class)))
                .thenAnswer(invocation -> {
                    invocation.<Consumer<Long>>getArgument(6).accept(3L);
                    return StockDeductionResult.success(stockVersion);
                });

        UserContext.set(new UserInfo(101L, "u101"));
        SeckillSubmitResponse response = service.submit(1L, 1001L);

        assertThat(response.status()).isEqualTo("ACCEPTED");
        verify(sentinelGuard).checkSubmit(true);
        verify(hotspotGuard).acquire(1L, 1001L);
    }

    @Test
    void guardedSubmitShouldUseExternalRequestIdForIdempotency() {
        SeckillServiceImpl service = newGuardedService();
        SeckillActivity activity = new SeckillActivity(1L, "flash", Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        SeckillSku sku = new SeckillSku(10L, 1L, 1001L, "phone", BigDecimal.valueOf(99), 50);
        StockVersion stockVersion = new StockVersion(49, 1L);
        SeckillBucketService.SelectedBucket selectedBucket = new SeckillBucketService.SelectedBucket(99L, 3, 3L, 7L, 1L);
        SeckillReservationGuardEntity guard = new SeckillReservationGuardEntity();
        guard.setReservationId("client-r1");
        guard.setRequestId("client-r1");
        guard.setStatus(ReservationGuardRepository.STATUS_PROCESSING);
        when(repository.requireActivity(1L)).thenReturn(activity);
        when(repository.requireSku(1L, 1001L)).thenReturn(sku);
        when(repository.isBucketModeEnabled()).thenReturn(true);
        when(guardRepository.createOrLoad(eq("client-r1"), any(ReservationGuardRepository.ReservationDraft.class)))
                .thenReturn(new ReservationGuardRepository.CreateGuardResult(
                        ReservationGuardRepository.GuardCreateOutcome.CREATED,
                        guard));
        when(repository.selectBucket(1L, 1001L)).thenReturn(selectedBucket);
        when(guardRepository.attachBucket(eq("client-r1"), eq(selectedBucket))).thenReturn(true);
        when(repository.recordDeduction(eq("client-r1"), eq(10L), eq(1L), eq(1001L), eq(101L), eq(1), eq(selectedBucket), any(Consumer.class)))
                .thenAnswer(invocation -> {
                    invocation.<Consumer<Long>>getArgument(7).accept(3L);
                    return StockDeductionResult.success(stockVersion);
                });

        UserContext.set(new UserInfo(101L, "u101"));
        SeckillSubmitResponse response = service.submit(1L, 1001L, " client-r1 ");

        assertThat(response.requestId()).isEqualTo("client-r1");
        ArgumentCaptor<ReservationGuardRepository.ReservationDraft> draftCaptor =
                ArgumentCaptor.forClass(ReservationGuardRepository.ReservationDraft.class);
        verify(guardRepository).createOrLoad(eq("client-r1"), draftCaptor.capture());
        assertThat(draftCaptor.getValue().reservationId()).isEqualTo("client-r1");
        verify(messagePublisher).enqueueSeckillOrderCreate(eq("client-r1"), anyString(), eq(3L));
    }

    @Test
    void shouldPrewarmMetadataAndTairStringStockCache() {
        SeckillServiceImpl service = newService(60_000);
        SeckillActivity activity = new SeckillActivity(1L, "flash", Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        SeckillSku sku = new SeckillSku(10L, 1L, 1001L, "phone", BigDecimal.valueOf(99), 50);
        StockVersion stockVersion = new StockVersion(50, 7L);
        when(repository.requireActivity(1L)).thenReturn(activity);
        when(repository.requireSku(1L, 1001L)).thenReturn(sku);
        when(repository.stockVersion(10L, 1L, 1001L)).thenReturn(stockVersion);

        service.prewarm(1L, 1001L);

        verify(repository).requireActivity(1L);
        verify(repository).requireSku(1L, 1001L);
        verify(stockCache).refresh(1L, 1001L, stockVersion);
    }

    private SeckillServiceImpl newService(long metadataCacheTtlMillis) {
        SeckillProperties properties = new SeckillProperties();
        properties.getLock().setEnabled(false);
        properties.setMetadataCacheTtlMillis(metadataCacheTtlMillis);
        return newService(properties, emptyReservationGuardProvider());
    }

    private SeckillServiceImpl newGuardedService() {
        SeckillProperties properties = new SeckillProperties();
        properties.getLock().setEnabled(false);
        properties.getReservationGuard().setEnabled(true);
        return newService(properties, guardRepositoryProvider());
    }

    private SeckillServiceImpl newService(SeckillProperties properties,
                                          ObjectProvider<ReservationGuardRepository> guardRepositoryProvider) {
        return new SeckillServiceImpl(
                repository,
                stockCache,
                sentinelGuard,
                hotspotGuard,
                messagePublisher,
                guardRepositoryProvider,
                new ObjectMapper(),
                emptyRedissonProvider(),
                properties,
                new SimpleMeterRegistry(),
                emptyTransactionManagerProvider()
        );
    }

    private ObjectProvider<ReservationGuardRepository> guardRepositoryProvider() {
        return new ObjectProvider<>() {
            @Override
            public ReservationGuardRepository getObject(Object... args) {
                return guardRepository;
            }

            @Override
            public ReservationGuardRepository getIfAvailable() {
                return guardRepository;
            }

            @Override
            public ReservationGuardRepository getIfUnique() {
                return guardRepository;
            }

            @Override
            public ReservationGuardRepository getObject() {
                return guardRepository;
            }
        };
    }

    private ObjectProvider<ReservationGuardRepository> emptyReservationGuardProvider() {
        return new ObjectProvider<>() {
            @Override
            public ReservationGuardRepository getObject(Object... args) {
                return null;
            }

            @Override
            public ReservationGuardRepository getIfAvailable() {
                return null;
            }

            @Override
            public ReservationGuardRepository getIfUnique() {
                return null;
            }

            @Override
            public ReservationGuardRepository getObject() {
                return null;
            }
        };
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

    private ObjectProvider<PlatformTransactionManager> emptyTransactionManagerProvider() {
        return new ObjectProvider<>() {
            @Override
            public PlatformTransactionManager getObject(Object... args) {
                return null;
            }

            @Override
            public PlatformTransactionManager getIfAvailable() {
                return null;
            }

            @Override
            public PlatformTransactionManager getIfUnique() {
                return null;
            }

            @Override
            public PlatformTransactionManager getObject() {
                return null;
            }
        };
    }
}

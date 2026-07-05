package com.mall.seckill.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.context.UserContext;
import com.mall.common.context.UserInfo;
import com.mall.message.ReliableMessagePublisher;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
        when(repository.recordDeduction(anyString(), eq(10L), eq(1L), eq(1001L), anyLong(), eq(1), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation.<Runnable>getArgument(6).run();
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
        verify(repository, times(2)).recordDeduction(anyString(), eq(10L), eq(1L), eq(1001L), anyLong(), eq(1), any(Runnable.class));
        verify(stockCache, times(2)).refresh(1L, 1001L, stockVersion);
        verify(messagePublisher, times(2)).enqueueSeckillOrderCreate(anyString(), anyString());
        verify(repository, never()).saveResult(any());
    }

    @Test
    void shouldKeepDuplicatePurchaseGuardInsideRecordDeduction() {
        SeckillServiceImpl service = newService(60_000);
        SeckillActivity activity = new SeckillActivity(1L, "flash", Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        SeckillSku sku = new SeckillSku(10L, 1L, 1001L, "phone", BigDecimal.valueOf(99), 50);
        when(repository.requireActivity(1L)).thenReturn(activity);
        when(repository.requireSku(1L, 1001L)).thenReturn(sku);
        when(repository.recordDeduction(anyString(), eq(10L), eq(1L), eq(1001L), eq(101L), eq(1), any(Runnable.class)))
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
        verify(messagePublisher, never()).enqueueSeckillOrderCreate(anyString(), anyString());
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
        verify(repository, never()).recordDeduction(anyString(), anyLong(), anyLong(), anyLong(), anyLong(), eq(1), any(Runnable.class));
        verify(messagePublisher, never()).enqueueSeckillOrderCreate(anyString(), anyString());
    }

    @Test
    void shouldReturnStockNotEnoughWhenOceanBaseDeductionFails() {
        SeckillServiceImpl service = newService(60_000);
        SeckillActivity activity = new SeckillActivity(1L, "flash", Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        SeckillSku sku = new SeckillSku(10L, 1L, 1001L, "phone", BigDecimal.valueOf(99), 50);
        when(repository.requireActivity(1L)).thenReturn(activity);
        when(repository.requireSku(1L, 1001L)).thenReturn(sku);
        when(repository.recordDeduction(anyString(), eq(10L), eq(1L), eq(1001L), eq(101L), eq(1), any(Runnable.class)))
                .thenThrow(new SeckillStockNotEnoughException());

        UserContext.set(new UserInfo(101L, "u101"));

        SeckillSubmitResponse response = service.submit(1L, 1001L);

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.message()).isEqualTo("Stock not enough");
        verify(stockCache, never()).refresh(anyLong(), anyLong(), any());
        verify(messagePublisher, never()).enqueueSeckillOrderCreate(anyString(), anyString());
    }

    @Test
    void shouldNotReleaseDeductionWhenOutboxEnqueueFails() {
        SeckillServiceImpl service = newService(60_000);
        SeckillActivity activity = new SeckillActivity(1L, "flash", Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        SeckillSku sku = new SeckillSku(10L, 1L, 1001L, "phone", BigDecimal.valueOf(99), 50);
        StockVersion deductedVersion = new StockVersion(49, 1L);
        when(repository.requireActivity(1L)).thenReturn(activity);
        when(repository.requireSku(1L, 1001L)).thenReturn(sku);
        when(repository.recordDeduction(anyString(), eq(10L), eq(1L), eq(1001L), eq(101L), eq(1), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation.<Runnable>getArgument(6).run();
                    return StockDeductionResult.success(deductedVersion);
                });
        doThrow(new RuntimeException("outbox failed"))
                .when(messagePublisher).enqueueSeckillOrderCreate(anyString(), anyString());

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
        when(repository.recordDeduction(anyString(), eq(10L), eq(1L), eq(1001L), eq(101L), eq(1), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation.<Runnable>getArgument(6).run();
                    return StockDeductionResult.success(stockVersion);
                });

        UserContext.set(new UserInfo(101L, "u101"));
        SeckillSubmitResponse response = service.submit(1L, 1001L);

        assertThat(response.status()).isEqualTo("ACCEPTED");
        verify(sentinelGuard).checkSubmit(true);
        verify(hotspotGuard).acquire(1L, 1001L);
    }

    @Test
    void shouldPrewarmMetadataAndTairStringStockCache() {
        SeckillServiceImpl service = newService(60_000);
        SeckillActivity activity = new SeckillActivity(1L, "flash", Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        SeckillSku sku = new SeckillSku(10L, 1L, 1001L, "phone", BigDecimal.valueOf(99), 50);
        StockVersion stockVersion = new StockVersion(50, 7L);
        when(repository.requireActivity(1L)).thenReturn(activity);
        when(repository.requireSku(1L, 1001L)).thenReturn(sku);
        when(repository.stockVersion(10L)).thenReturn(stockVersion);

        service.prewarm(1L, 1001L);

        verify(repository).requireActivity(1L);
        verify(repository).requireSku(1L, 1001L);
        verify(stockCache).refresh(1L, 1001L, stockVersion);
    }

    private SeckillServiceImpl newService(long metadataCacheTtlMillis) {
        SeckillProperties properties = new SeckillProperties();
        properties.getLock().setEnabled(false);
        properties.setMetadataCacheTtlMillis(metadataCacheTtlMillis);
        return new SeckillServiceImpl(
                repository,
                stockCache,
                sentinelGuard,
                hotspotGuard,
                messagePublisher,
                new ObjectMapper(),
                emptyRedissonProvider(),
                properties,
                new SimpleMeterRegistry(),
                emptyTransactionManagerProvider()
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

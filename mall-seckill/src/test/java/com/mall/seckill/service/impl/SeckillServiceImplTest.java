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
import com.mall.seckill.pojo.vo.StockReleaseResult;
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
        when(repository.recordDeduction(anyString(), eq(10L), eq(1L), eq(1001L), anyLong(), eq(1)))
                .thenReturn(StockDeductionResult.success(stockVersion));

        UserContext.set(new UserInfo(101L, "u101"));
        SeckillSubmitResponse first = service.submit(1L, 1001L);
        UserContext.set(new UserInfo(102L, "u102"));
        SeckillSubmitResponse second = service.submit(1L, 1001L);

        assertThat(first.status()).isEqualTo("ACCEPTED");
        assertThat(second.status()).isEqualTo("ACCEPTED");
        verify(repository, times(1)).requireActivity(1L);
        verify(repository, times(1)).requireSku(1L, 1001L);
        verify(repository, never()).hasActiveDeduction(anyLong(), anyLong(), anyLong());
        verify(repository, times(2)).recordDeduction(anyString(), eq(10L), eq(1L), eq(1001L), anyLong(), eq(1));
        verify(stockCache, times(2)).refresh(1L, 1001L, stockVersion);
        verify(messagePublisher, times(2)).publishSeckillOrderCreate(anyString(), anyString());
        verify(repository, never()).saveResult(any());
    }

    @Test
    void shouldKeepDuplicatePurchaseGuardInsideRecordDeduction() {
        SeckillServiceImpl service = newService(60_000);
        SeckillActivity activity = new SeckillActivity(1L, "flash", Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        SeckillSku sku = new SeckillSku(10L, 1L, 1001L, "phone", BigDecimal.valueOf(99), 50);
        when(repository.requireActivity(1L)).thenReturn(activity);
        when(repository.requireSku(1L, 1001L)).thenReturn(sku);
        when(repository.recordDeduction(anyString(), eq(10L), eq(1L), eq(1001L), eq(101L), eq(1)))
                .thenReturn(StockDeductionResult.duplicate());

        UserContext.set(new UserInfo(101L, "u101"));
        SeckillSubmitResponse response = service.submit(1L, 1001L);

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.message()).isEqualTo("Duplicate purchase");
        ArgumentCaptor<SeckillResult> resultCaptor = ArgumentCaptor.forClass(SeckillResult.class);
        verify(repository).saveResult(resultCaptor.capture());
        assertThat(resultCaptor.getValue().status()).isEqualTo("FAILED");
        assertThat(resultCaptor.getValue().message()).isEqualTo("Duplicate purchase");
        verify(repository, never()).hasActiveDeduction(anyLong(), anyLong(), anyLong());
        verify(messagePublisher, never()).publishSeckillOrderCreate(anyString(), anyString());
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
        verify(repository, never()).recordDeduction(anyString(), anyLong(), anyLong(), anyLong(), anyLong(), eq(1));
        verify(messagePublisher, never()).publishSeckillOrderCreate(anyString(), anyString());
    }

    @Test
    void shouldReturnStockNotEnoughWhenOceanBaseDeductionFails() {
        SeckillServiceImpl service = newService(60_000);
        SeckillActivity activity = new SeckillActivity(1L, "flash", Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        SeckillSku sku = new SeckillSku(10L, 1L, 1001L, "phone", BigDecimal.valueOf(99), 50);
        when(repository.requireActivity(1L)).thenReturn(activity);
        when(repository.requireSku(1L, 1001L)).thenReturn(sku);
        when(repository.recordDeduction(anyString(), eq(10L), eq(1L), eq(1001L), eq(101L), eq(1)))
                .thenThrow(new SeckillStockNotEnoughException());

        UserContext.set(new UserInfo(101L, "u101"));

        SeckillSubmitResponse response = service.submit(1L, 1001L);

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.message()).isEqualTo("Stock not enough");
        verify(stockCache, never()).refresh(anyLong(), anyLong(), any());
        verify(messagePublisher, never()).publishSeckillOrderCreate(anyString(), anyString());
    }

    @Test
    void shouldReleaseDeductionRefreshCacheAndSaveFailureWhenMessagePublishFails() {
        SeckillServiceImpl service = newService(60_000);
        SeckillActivity activity = new SeckillActivity(1L, "flash", Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        SeckillSku sku = new SeckillSku(10L, 1L, 1001L, "phone", BigDecimal.valueOf(99), 50);
        StockVersion deductedVersion = new StockVersion(49, 1L);
        StockVersion releasedVersion = new StockVersion(50, 2L);
        when(repository.requireActivity(1L)).thenReturn(activity);
        when(repository.requireSku(1L, 1001L)).thenReturn(sku);
        when(repository.recordDeduction(anyString(), eq(10L), eq(1L), eq(1001L), eq(101L), eq(1)))
                .thenReturn(StockDeductionResult.success(deductedVersion));
        when(repository.releaseDeduction(anyString(), eq("Order message publish failed")))
                .thenReturn(new StockReleaseResult(
                        new SeckillRepository.StockSnapshot("r1", 1L, 1001L, 101L, 1, "RELEASED"),
                        releasedVersion));
        doThrow(new RuntimeException("publish failed"))
                .when(messagePublisher).publishSeckillOrderCreate(anyString(), anyString());

        UserContext.set(new UserInfo(101L, "u101"));

        SeckillSubmitResponse response = service.submit(1L, 1001L);

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.message()).isEqualTo("Order message publish failed");
        verify(stockCache).refresh(1L, 1001L, deductedVersion);
        verify(stockCache).refresh(1L, 1001L, releasedVersion);
        ArgumentCaptor<SeckillResult> resultCaptor = ArgumentCaptor.forClass(SeckillResult.class);
        verify(repository).saveResult(resultCaptor.capture());
        assertThat(resultCaptor.getValue().status()).isEqualTo("FAILED");
        assertThat(resultCaptor.getValue().message()).isEqualTo("Order message publish failed");
    }

    private SeckillServiceImpl newService(long metadataCacheTtlMillis) {
        SeckillProperties properties = new SeckillProperties();
        properties.getLock().setEnabled(false);
        properties.setMetadataCacheTtlMillis(metadataCacheTtlMillis);
        return new SeckillServiceImpl(
                repository,
                stockCache,
                sentinelGuard,
                messagePublisher,
                new ObjectMapper(),
                emptyRedissonProvider(),
                properties,
                new SimpleMeterRegistry()
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

package com.mall.seckill.service.impl;

import com.mall.common.exception.BusinessException;
import com.mall.seckill.config.SeckillProperties;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SeckillEntryGuardTest {

    @Test
    void shouldAllowRequestAndBuyerWhenDisabledWithoutRedisson() {
        ObjectProvider<RedissonClient> provider = provider(null);
        SeckillEntryGuard guard = new SeckillEntryGuard(provider, new SeckillProperties());

        SeckillEntryGuard.RequestDecision requestDecision = guard.acquireRequest("r1");
        SeckillEntryGuard.BuyerDecision buyerDecision = guard.acquireBuyer(
                1L, 1001L, 101L, "r1", Instant.now().plusSeconds(60));

        assertThat(guard.enabled()).isFalse();
        assertThat(requestDecision.outcome()).isEqualTo(SeckillEntryGuard.RequestOutcome.ACQUIRED);
        assertThat(buyerDecision.outcome()).isEqualTo(SeckillEntryGuard.BuyerOutcome.ACQUIRED);
        assertThat(buyerDecision.existingRequestId()).isNull();
        verifyNoInteractions(provider);
    }

    @Test
    void shouldThrowWhenEnabledButRedissonUnavailable() {
        SeckillEntryGuard guard = new SeckillEntryGuard(provider(null), enabledProperties());

        assertThatThrownBy(() -> guard.acquireRequest("r1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Seckill entry guard unavailable");
        assertThatThrownBy(() -> guard.acquireBuyer(1L, 1001L, 101L, "r1", Instant.now().plusSeconds(60)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Seckill entry guard unavailable");
    }

    @Test
    void shouldAcquireRequestWithConfiguredKeyAndTtl() {
        RedissonClient redisson = mock(RedissonClient.class);
        RBucket<String> bucket = bucket(redisson, "seckill:entry:req:r1");
        when(bucket.trySet("1", 300L, TimeUnit.SECONDS)).thenReturn(true);
        SeckillEntryGuard guard = new SeckillEntryGuard(provider(redisson), enabledProperties());

        SeckillEntryGuard.RequestDecision decision = guard.acquireRequest("r1");

        assertThat(decision.outcome()).isEqualTo(SeckillEntryGuard.RequestOutcome.ACQUIRED);
        verify(bucket).trySet("1", 300L, TimeUnit.SECONDS);
    }

    @Test
    void shouldReturnDuplicateWhenRequestKeyAlreadyExists() {
        RedissonClient redisson = mock(RedissonClient.class);
        RBucket<String> bucket = bucket(redisson, "seckill:entry:req:r1");
        when(bucket.trySet("1", 300L, TimeUnit.SECONDS)).thenReturn(false);
        SeckillEntryGuard guard = new SeckillEntryGuard(provider(redisson), enabledProperties());

        SeckillEntryGuard.RequestDecision decision = guard.acquireRequest("r1");

        assertThat(decision.outcome()).isEqualTo(SeckillEntryGuard.RequestOutcome.DUPLICATE);
    }

    @Test
    void shouldAcquireBuyerWithConfiguredKeyAndBufferedTtl() {
        RedissonClient redisson = mock(RedissonClient.class);
        RBucket<String> bucket = bucket(redisson, "seckill:entry:buyer:1:1001:101");
        when(bucket.trySet(eq("r1"), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);
        SeckillEntryGuard guard = new SeckillEntryGuard(provider(redisson), enabledProperties());

        SeckillEntryGuard.BuyerDecision decision = guard.acquireBuyer(
                1L, 1001L, 101L, "r1", Instant.now().plusSeconds(60));

        assertThat(decision.outcome()).isEqualTo(SeckillEntryGuard.BuyerOutcome.ACQUIRED);
        verify(bucket).trySet(eq("r1"), org.mockito.ArgumentMatchers.longThat(ttl -> ttl >= 600), eq(TimeUnit.SECONDS));
    }

    @Test
    void shouldReturnSameRequestWhenBuyerKeyContainsCurrentRequestId() {
        RedissonClient redisson = mock(RedissonClient.class);
        RBucket<String> bucket = bucket(redisson, "seckill:entry:buyer:1:1001:101");
        when(bucket.trySet(eq("r1"), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(false);
        when(bucket.get()).thenReturn("r1");
        SeckillEntryGuard guard = new SeckillEntryGuard(provider(redisson), enabledProperties());

        SeckillEntryGuard.BuyerDecision decision = guard.acquireBuyer(
                1L, 1001L, 101L, "r1", Instant.now().plusSeconds(60));

        assertThat(decision.outcome()).isEqualTo(SeckillEntryGuard.BuyerOutcome.SAME_REQUEST);
        assertThat(decision.existingRequestId()).isEqualTo("r1");
    }

    @Test
    void shouldReturnDuplicateBuyerWithExistingRequestId() {
        RedissonClient redisson = mock(RedissonClient.class);
        RBucket<String> bucket = bucket(redisson, "seckill:entry:buyer:1:1001:101");
        when(bucket.trySet(eq("r1"), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(false);
        when(bucket.get()).thenReturn("r0");
        SeckillEntryGuard guard = new SeckillEntryGuard(provider(redisson), enabledProperties());

        SeckillEntryGuard.BuyerDecision decision = guard.acquireBuyer(
                1L, 1001L, 101L, "r1", Instant.now().plusSeconds(60));

        assertThat(decision.outcome()).isEqualTo(SeckillEntryGuard.BuyerOutcome.DUPLICATE_BUYER);
        assertThat(decision.existingRequestId()).isEqualTo("r0");
    }

    @Test
    void shouldReleaseBuyerOnlyWhenCurrentRequestIdMatches() {
        RedissonClient redisson = mock(RedissonClient.class);
        RBucket<String> bucket = bucket(redisson, "seckill:entry:buyer:1:1001:101");
        SeckillEntryGuard guard = new SeckillEntryGuard(provider(redisson), enabledProperties());
        when(bucket.get()).thenReturn("r1", "r0");

        guard.releaseBuyer(1L, 1001L, 101L, "r1");
        verify(bucket).delete();

        clearInvocations(bucket);
        guard.releaseBuyer(1L, 1001L, 101L, "r1");
        verify(bucket, never()).delete();
    }

    private SeckillProperties enabledProperties() {
        SeckillProperties properties = new SeckillProperties();
        properties.getEntryGuard().setEnabled(true);
        return properties;
    }

    private ObjectProvider<RedissonClient> provider(RedissonClient redisson) {
        ObjectProvider<RedissonClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redisson);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private RBucket<String> bucket(RedissonClient redisson, String key) {
        RBucket<String> bucket = mock(RBucket.class);
        when(redisson.<String>getBucket(key)).thenReturn(bucket);
        return bucket;
    }
}

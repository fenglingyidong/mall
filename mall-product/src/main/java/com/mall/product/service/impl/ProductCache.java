package com.mall.product.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.util.JsonUtils;
import com.mall.product.pojo.vo.ProductCoreDetail;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ProductCache {

    private static final Duration TTL = Duration.ofMinutes(10);

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final Map<Long, CacheEntry> localCache = new ConcurrentHashMap<>();

    public ProductCache(ObjectMapper objectMapper, ObjectProvider<StringRedisTemplate> redisTemplate) {
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate.getIfAvailable();
    }

    public ProductCoreDetail get(Long skuId) {
        CacheEntry entry = localCache.get(skuId);
        if (entry != null && entry.expiresAt().isAfter(Instant.now())) {
            return entry.coreDetail();
        }
        if (redisTemplate == null) {
            return null;
        }
        try {
            String value = redisTemplate.opsForValue().get(key(skuId));
            if (value == null) {
                return null;
            }
            ProductCoreDetail coreDetail = JsonUtils.fromJson(objectMapper, value, ProductCoreDetail.class);
            localCache.put(skuId, new CacheEntry(coreDetail, Instant.now().plusSeconds(60)));
            return coreDetail;
        } catch (Exception ignored) {
            return null;
        }
    }

    public void put(Long skuId, ProductCoreDetail coreDetail) {
        localCache.put(skuId, new CacheEntry(coreDetail, Instant.now().plusSeconds(60)));
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key(skuId), JsonUtils.toJson(objectMapper, coreDetail), TTL);
        } catch (Exception ignored) {
            // Redis is optional for local demos.
        }
    }

    public void invalidate(Long skuId) {
        localCache.remove(skuId);
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(key(skuId));
            } catch (Exception ignored) {
                // Ignore Redis connection errors.
            }
        }
    }

    private String key(Long skuId) {
        return "product:detail:" + skuId;
    }

    private record CacheEntry(ProductCoreDetail coreDetail, Instant expiresAt) {
    }
}

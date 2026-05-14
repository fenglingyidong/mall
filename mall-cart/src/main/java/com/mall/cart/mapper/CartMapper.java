package com.mall.cart.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.cart.pojo.entity.CartItem;
import com.mall.cart.pojo.entity.CartItemEntity;
import com.mall.common.util.JsonUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
public class CartMapper {

    private static final String CACHE_LOADED_FIELD = "_loaded";

    private final CartItemMapper cartItemMapper;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    public CartMapper(CartItemMapper cartItemMapper,
                      ObjectMapper objectMapper,
                      ObjectProvider<StringRedisTemplate> redisTemplate) {
        this.cartItemMapper = cartItemMapper;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate.getIfAvailable();
    }

    public void save(Long userId, CartItem item) {
        cartItemMapper.upsert(toEntity(userId, item));
        cachePut(userId, item);
    }

    public CartItem findOne(Long userId, Long skuId) {
        CartItem cached = cacheGet(userId, skuId);
        if (cached != null) {
            return cached;
        }
        CartItemEntity entity = findEntity(userId, skuId);
        if (entity == null) {
            return null;
        }
        CartItem item = toDomain(entity);
        cachePut(userId, item);
        return item;
    }

    public List<CartItem> findAll(Long userId) {
        List<CartItem> cached = cacheAll(userId);
        if (cached != null) {
            return cached;
        }
        List<CartItem> items = cartItemMapper.selectList(Wrappers.<CartItemEntity>lambdaQuery()
                        .eq(CartItemEntity::getUserId, userId))
                .stream()
                .map(this::toDomain)
                .toList();
        cacheReplace(userId, items);
        return new ArrayList<>(items);
    }

    public void delete(Long userId, Long skuId) {
        cartItemMapper.delete(Wrappers.<CartItemEntity>lambdaQuery()
                .eq(CartItemEntity::getUserId, userId)
                .eq(CartItemEntity::getSkuId, skuId));
        cacheDelete(userId, skuId);
    }

    private CartItemEntity findEntity(Long userId, Long skuId) {
        return cartItemMapper.selectOne(Wrappers.<CartItemEntity>lambdaQuery()
                .eq(CartItemEntity::getUserId, userId)
                .eq(CartItemEntity::getSkuId, skuId));
    }

    private CartItemEntity toEntity(Long userId, CartItem item) {
        CartItemEntity entity = new CartItemEntity();
        entity.setUserId(userId);
        entity.setSkuId(item.skuId());
        entity.setSkuName(item.skuName());
        entity.setPrice(item.price());
        entity.setQuantity(item.quantity());
        entity.setChecked(item.checked());
        return entity;
    }

    private CartItem toDomain(CartItemEntity entity) {
        return new CartItem(
                entity.getSkuId(),
                entity.getSkuName(),
                entity.getPrice(),
                entity.getQuantity(),
                entity.getChecked()
        );
    }

    private CartItem cacheGet(Long userId, Long skuId) {
        if (!hasRedis()) {
            return null;
        }
        try {
            if (Boolean.FALSE.equals(redisTemplate.opsForHash().hasKey(key(userId), CACHE_LOADED_FIELD))) {
                return null;
            }
            Object value = redisTemplate.opsForHash().get(key(userId), field(skuId));
            return value == null ? null : JsonUtils.fromJson(objectMapper, value.toString(), CartItem.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<CartItem> cacheAll(Long userId) {
        if (!hasRedis()) {
            return null;
        }
        try {
            if (Boolean.FALSE.equals(redisTemplate.opsForHash().hasKey(key(userId), CACHE_LOADED_FIELD))) {
                return null;
            }
            return new ArrayList<>(redisTemplate.opsForHash().values(key(userId)).stream()
                    .filter(value -> !CACHE_LOADED_FIELD.equals(value.toString()))
                    .map(value -> JsonUtils.fromJson(objectMapper, value.toString(), CartItem.class))
                    .toList());
        } catch (Exception ignored) {
            return null;
        }
    }

    private void cachePut(Long userId, CartItem item) {
        if (!hasRedis()) {
            return;
        }
        try {
            if (Boolean.FALSE.equals(redisTemplate.opsForHash().hasKey(key(userId), CACHE_LOADED_FIELD))) {
                return;
            }
            redisTemplate.opsForHash().put(key(userId), field(item.skuId()), JsonUtils.toJson(objectMapper, item));
        } catch (Exception ignored) {
            // Redis is only a cache. MySQL already holds the source of truth.
        }
    }

    private void cacheReplace(Long userId, List<CartItem> items) {
        if (!hasRedis()) {
            return;
        }
        try {
            redisTemplate.delete(key(userId));
            items.forEach(item -> redisTemplate.opsForHash()
                    .put(key(userId), field(item.skuId()), JsonUtils.toJson(objectMapper, item)));
            redisTemplate.opsForHash().put(key(userId), CACHE_LOADED_FIELD, CACHE_LOADED_FIELD);
        } catch (Exception ignored) {
            // Redis is only a cache. MySQL already holds the source of truth.
        }
    }

    private void cacheDelete(Long userId, Long skuId) {
        if (!hasRedis()) {
            return;
        }
        try {
            redisTemplate.opsForHash().delete(key(userId), field(skuId));
        } catch (Exception ignored) {
            // Redis is only a cache. MySQL already holds the source of truth.
        }
    }

    private boolean hasRedis() {
        return redisTemplate != null;
    }

    private String key(Long userId) {
        return "cart:" + userId;
    }

    private String field(Long skuId) {
        return String.valueOf(skuId);
    }
}

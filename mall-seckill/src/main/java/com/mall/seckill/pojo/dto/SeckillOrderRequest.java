package com.mall.seckill.pojo.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record SeckillOrderRequest(
        @NotBlank String requestId,
        String reservationId,
        @NotNull @Min(1) Long activityId,
        @NotNull @Min(1) Long userId,
        @NotNull @Min(1) Long skuId,
        @NotBlank String skuName,
        @NotNull @DecimalMin("0.00") BigDecimal price,
        @NotNull @Min(1) Integer quantity,
        Long bucketShardKey
) {
    public SeckillOrderRequest(String requestId,
                               Long activityId,
                               Long userId,
                               Long skuId,
                               String skuName,
                               BigDecimal price,
                               Integer quantity) {
        this(requestId, requestId, activityId, userId, skuId, skuName, price, quantity, null);
    }

    public SeckillOrderRequest(String requestId,
                               Long activityId,
                               Long userId,
                               Long skuId,
                               String skuName,
                               BigDecimal price,
                               Integer quantity,
                               Long bucketShardKey) {
        this(requestId, requestId, activityId, userId, skuId, skuName, price, quantity, bucketShardKey);
    }

    public SeckillOrderRequest {
        if (reservationId == null || reservationId.isBlank()) {
            reservationId = requestId;
        }
    }

    public SeckillOrderRequest withBucketShardKey(Long bucketShardKey) {
        return new SeckillOrderRequest(requestId, reservationId, activityId, userId, skuId, skuName, price, quantity, bucketShardKey);
    }
}



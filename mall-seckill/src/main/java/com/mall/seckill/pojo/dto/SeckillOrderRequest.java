package com.mall.seckill.pojo.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record SeckillOrderRequest(
        @NotBlank String requestId,
        @NotNull @Min(1) Long activityId,
        @NotNull @Min(1) Long userId,
        @NotNull @Min(1) Long skuId,
        @NotBlank String skuName,
        @NotNull @DecimalMin("0.00") BigDecimal price,
        @NotNull @Min(1) Integer quantity
) {
}



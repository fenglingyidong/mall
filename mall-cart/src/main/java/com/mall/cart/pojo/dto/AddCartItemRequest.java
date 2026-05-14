package com.mall.cart.pojo.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record AddCartItemRequest(
        @NotNull Long skuId,
        @Size(max = 128) String skuName,
        @DecimalMin("0.00") BigDecimal price,
        @NotNull @Min(1) Integer quantity
) {
}



package com.mall.product.pojo.entity;

import java.math.BigDecimal;

public record Sku(Long skuId, Long spuId, Long categoryId, Long brandId, String skuName, BigDecimal price) {
}



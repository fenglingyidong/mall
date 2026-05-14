package com.mall.common.model;

import java.math.BigDecimal;

public record SkuSnapshot(Long skuId, String skuName, BigDecimal price, Integer stock) {
}



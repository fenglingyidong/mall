package com.mall.common.client.vo;

import java.math.BigDecimal;

public record ProductSearchItem(
        Long skuId,
        Long spuId,
        String skuName,
        String spuName,
        String brandName,
        String categoryName,
        BigDecimal price,
        Integer stock
) {
}

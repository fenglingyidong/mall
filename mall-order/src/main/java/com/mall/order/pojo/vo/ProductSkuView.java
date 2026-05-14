package com.mall.order.pojo.vo;

import java.math.BigDecimal;
import java.util.List;

public record ProductSkuView(
        Long skuId,
        Long spuId,
        String skuName,
        String spuName,
        String categoryName,
        String brandName,
        BigDecimal price,
        Integer stock,
        String promotion,
        List<Object> skuOptions
) {
}



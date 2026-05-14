package com.mall.product.pojo.vo;

import java.math.BigDecimal;
import java.util.List;

public record ProductCoreDetail(
        Long skuId,
        Long spuId,
        String skuName,
        String spuName,
        String categoryName,
        String brandName,
        BigDecimal price,
        Integer stock,
        String promotion,
        List<SkuOption> skuOptions
) {
}

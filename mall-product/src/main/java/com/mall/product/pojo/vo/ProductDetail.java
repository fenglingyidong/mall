package com.mall.product.pojo.vo;

import java.math.BigDecimal;
import java.util.List;

public record ProductDetail(
        Long skuId,
        Long spuId,
        String skuName,
        String spuName,
        String categoryName,
        String brandName,
        BigDecimal price,
        Integer stock,
        String promotion,
        List<SkuOption> skuOptions,
        ReviewSummary reviewSummary,
        List<CouponView> coupons
) {
}



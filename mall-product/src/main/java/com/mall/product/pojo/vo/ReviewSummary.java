package com.mall.product.pojo.vo;

import java.math.BigDecimal;

public record ReviewSummary(
        Long skuId,
        BigDecimal averageRating,
        Integer reviewCount,
        BigDecimal goodRate,
        String latestReview
) {

    public static ReviewSummary empty(Long skuId) {
        return new ReviewSummary(skuId, BigDecimal.ZERO, 0, BigDecimal.ZERO, null);
    }
}

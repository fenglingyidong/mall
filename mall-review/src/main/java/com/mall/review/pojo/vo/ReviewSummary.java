package com.mall.review.pojo.vo;

import java.math.BigDecimal;

public record ReviewSummary(
        Long skuId,
        BigDecimal averageRating,
        Integer reviewCount,
        BigDecimal goodRate,
        String latestReview
) {
}

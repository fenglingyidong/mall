package com.mall.product.pojo.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CouponView(
        Long couponId,
        String title,
        BigDecimal thresholdAmount,
        BigDecimal discountAmount,
        LocalDateTime expireAt
) {
}

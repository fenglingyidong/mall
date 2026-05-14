package com.mall.coupon.pojo.vo;

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

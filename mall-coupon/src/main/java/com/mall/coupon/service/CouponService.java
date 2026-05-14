package com.mall.coupon.service;

import com.mall.coupon.pojo.vo.CouponView;

import java.util.List;

public interface CouponService {

    List<CouponView> available(Long skuId, Long userId);
}

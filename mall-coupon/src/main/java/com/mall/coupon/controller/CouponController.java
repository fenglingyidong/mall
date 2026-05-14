package com.mall.coupon.controller;

import com.mall.coupon.pojo.vo.CouponView;
import com.mall.coupon.service.CouponService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class CouponController {

    private final CouponService couponService;

    public CouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    @GetMapping("/internal/coupon/sku/{skuId}/available")
    public List<CouponView> available(@PathVariable Long skuId,
                                      @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        return couponService.available(skuId, userId);
    }
}

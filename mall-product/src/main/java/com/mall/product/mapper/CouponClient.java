package com.mall.product.mapper;

import com.mall.product.pojo.vo.CouponView;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;

@FeignClient(name = "mall-coupon", fallback = CouponClientFallback.class)
public interface CouponClient {

    @GetMapping("/internal/coupon/sku/{skuId}/available")
    List<CouponView> available(@PathVariable("skuId") Long skuId,
                               @RequestHeader("X-User-Id") Long userId);
}

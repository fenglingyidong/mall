package com.mall.order.mapper;

import com.mall.common.api.ApiResponse;
import com.mall.order.pojo.vo.CartItemView;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;

@FeignClient(name = "mall-cart", fallback = CartClientFallback.class)
public interface CartClient {

    @GetMapping("/internal/cart/selected")
    List<CartItemView> selected(@RequestHeader("X-User-Id") Long userId);

    @PostMapping("/internal/cart/selected/clear")
    ApiResponse<Void> clearSelected(@RequestHeader("X-User-Id") Long userId);
}

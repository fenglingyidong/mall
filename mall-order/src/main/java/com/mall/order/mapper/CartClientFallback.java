package com.mall.order.mapper;

import com.mall.common.api.ApiResponse;
import com.mall.order.pojo.vo.CartItemView;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class CartClientFallback implements CartClient {

    @Override
    public List<CartItemView> selected(Long userId) {
        return List.of(new CartItemView(1001L, "Headphones Black", new BigDecimal("699.00"), 1, true));
    }

    @Override
    public ApiResponse<Void> clearSelected(Long userId) {
        return ApiResponse.success();
    }
}

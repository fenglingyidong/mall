package com.mall.cart.controller;

import com.mall.cart.pojo.dto.AddCartItemRequest;
import com.mall.cart.pojo.dto.UpdateCartItemRequest;
import com.mall.cart.pojo.entity.CartItem;
import com.mall.cart.service.CartService;
import com.mall.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping("/api/cart")
    public ApiResponse<List<CartItem>> list() {
        return ApiResponse.success(cartService.list());
    }

    @PostMapping("/api/cart/items")
    public ApiResponse<CartItem> add(@Valid @RequestBody AddCartItemRequest request) {
        return ApiResponse.success(cartService.add(request));
    }

    @PutMapping("/api/cart/items/{skuId}")
    public ApiResponse<CartItem> update(@PathVariable Long skuId, @Valid @RequestBody UpdateCartItemRequest request) {
        return ApiResponse.success(cartService.update(skuId, request));
    }

    @DeleteMapping("/api/cart/items/{skuId}")
    public ApiResponse<Void> remove(@PathVariable Long skuId) {
        cartService.remove(skuId);
        return ApiResponse.success();
    }

    @GetMapping("/internal/cart/selected")
    public List<CartItem> selected(@RequestHeader("X-User-Id") Long userId) {
        return cartService.selected(userId);
    }

    @PostMapping("/internal/cart/selected/clear")
    public ApiResponse<Void> clearSelected(@RequestHeader("X-User-Id") Long userId) {
        cartService.clearSelected(userId);
        return ApiResponse.success();
    }
}

package com.mall.cart.service;

import com.mall.cart.pojo.dto.AddCartItemRequest;
import com.mall.cart.pojo.dto.UpdateCartItemRequest;
import com.mall.cart.pojo.entity.CartItem;

import java.util.List;

public interface CartService {

    List<CartItem> list();

    CartItem add(AddCartItemRequest request);

    CartItem update(Long skuId, UpdateCartItemRequest request);

    void remove(Long skuId);

    List<CartItem> selected(Long userId);

    void clearSelected(Long userId);
}

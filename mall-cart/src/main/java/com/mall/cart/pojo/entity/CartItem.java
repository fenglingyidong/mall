package com.mall.cart.pojo.entity;

import java.math.BigDecimal;

public record CartItem(
        Long skuId,
        String skuName,
        BigDecimal price,
        Integer quantity,
        Boolean checked
) {

    public CartItem withQuantity(Integer newQuantity) {
        return new CartItem(skuId, skuName, price, newQuantity, checked);
    }

    public CartItem withChecked(Boolean newChecked) {
        return new CartItem(skuId, skuName, price, quantity, newChecked);
    }
}



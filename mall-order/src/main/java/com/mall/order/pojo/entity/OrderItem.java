package com.mall.order.pojo.entity;

import java.math.BigDecimal;

public record OrderItem(Long skuId, String skuName, BigDecimal price, Integer quantity, BigDecimal amount) {
}



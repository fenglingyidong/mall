package com.mall.order.pojo.vo;

import java.math.BigDecimal;

public record CartItemView(Long skuId, String skuName, BigDecimal price, Integer quantity, Boolean checked) {
}



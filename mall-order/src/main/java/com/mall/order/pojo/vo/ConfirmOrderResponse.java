package com.mall.order.pojo.vo;

import com.mall.order.pojo.entity.OrderItem;

import java.math.BigDecimal;
import java.util.List;

public record ConfirmOrderResponse(Long userId, List<OrderItem> items, BigDecimal totalAmount) {
}

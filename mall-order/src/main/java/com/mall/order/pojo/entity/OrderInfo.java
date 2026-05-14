package com.mall.order.pojo.entity;

import com.mall.common.model.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderInfo(
        String orderSn,
        Long userId,
        OrderStatus status,
        BigDecimal totalAmount,
        List<OrderItem> items,
        String source,
        Instant createdAt,
        Instant updatedAt
) {

    public OrderInfo withStatus(OrderStatus newStatus) {
        return new OrderInfo(orderSn, userId, newStatus, totalAmount, items, source, createdAt, Instant.now());
    }
}



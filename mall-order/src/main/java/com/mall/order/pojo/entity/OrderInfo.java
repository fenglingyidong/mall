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
        String sourceId,
        Instant createdAt,
        Instant updatedAt
) {
    public OrderInfo(String orderSn,
                     Long userId,
                     OrderStatus status,
                     BigDecimal totalAmount,
                     List<OrderItem> items,
                     String source,
                     Instant createdAt,
                     Instant updatedAt) {
        this(orderSn, userId, status, totalAmount, items, source, null, createdAt, updatedAt);
    }

    public OrderInfo withStatus(OrderStatus newStatus) {
        return new OrderInfo(orderSn, userId, newStatus, totalAmount, items, source, sourceId, createdAt, Instant.now());
    }
}

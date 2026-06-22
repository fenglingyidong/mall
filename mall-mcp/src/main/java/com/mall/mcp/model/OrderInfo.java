package com.mall.mcp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderInfo(
        String orderSn,
        Long userId,
        String status,
        BigDecimal totalAmount,
        List<OrderItem> items,
        String source,
        Instant createdAt,
        Instant updatedAt
) {
}

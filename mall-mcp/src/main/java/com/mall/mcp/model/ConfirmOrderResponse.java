package com.mall.mcp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfirmOrderResponse(Long userId, List<OrderItem> items, BigDecimal totalAmount) {
}

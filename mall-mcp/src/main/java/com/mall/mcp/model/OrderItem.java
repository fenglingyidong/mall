package com.mall.mcp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderItem(Long skuId, String skuName, BigDecimal price, Integer quantity, BigDecimal amount) {
}

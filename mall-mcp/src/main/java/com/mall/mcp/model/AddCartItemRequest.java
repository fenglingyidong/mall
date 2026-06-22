package com.mall.mcp.model;

import java.math.BigDecimal;

public record AddCartItemRequest(Long skuId, String skuName, BigDecimal price, Integer quantity) {
}

package com.mall.mcp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductSearchItem(
        Long skuId,
        Long spuId,
        String skuName,
        String spuName,
        String brandName,
        String categoryName,
        BigDecimal price,
        Integer stock
) {
}

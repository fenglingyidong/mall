package com.mall.mcp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductDetail(
        Long skuId,
        Long spuId,
        String skuName,
        String spuName,
        String categoryName,
        String brandName,
        BigDecimal price,
        Integer stock,
        String promotion,
        List<Object> skuOptions,
        Object reviewSummary,
        List<Object> coupons
) {
}

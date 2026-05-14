package com.mall.product.pojo.entity;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProductDetailRow {

    private Long skuId;

    private Long spuId;

    private String skuName;

    private String spuName;

    private String categoryName;

    private String brandName;

    private BigDecimal price;

    private Integer stock;
}

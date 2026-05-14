package com.mall.product.pojo.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("sku")
public class SkuEntity {

    @TableId
    private Long id;

    private Long spuId;

    private String name;

    private BigDecimal price;
}

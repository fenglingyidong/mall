package com.mall.seckill.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("seckill_sku")
public class SeckillSkuEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long activityId;

    private Long skuId;

    private String skuName;

    private BigDecimal seckillPrice;

    private Integer stock;
}

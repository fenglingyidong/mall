package com.mall.order.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("order_item")
public class OrderItemEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderSn;

    private Long skuId;

    private String skuName;

    private BigDecimal price;

    private Integer quantity;

    private BigDecimal amount;
}

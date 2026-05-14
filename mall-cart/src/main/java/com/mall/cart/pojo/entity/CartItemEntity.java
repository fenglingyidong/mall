package com.mall.cart.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("cart_item")
public class CartItemEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long skuId;

    private String skuName;

    private BigDecimal price;

    private Integer quantity;

    private Boolean checked;
}

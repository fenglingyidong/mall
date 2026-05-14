package com.mall.coupon.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("product_coupon")
public class ProductCouponEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long skuId;

    private String title;

    private BigDecimal thresholdAmount;

    private BigDecimal discountAmount;

    private LocalDateTime expireAt;

    private Integer stock;

    private Boolean enabled;
}

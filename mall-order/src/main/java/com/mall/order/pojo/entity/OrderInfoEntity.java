package com.mall.order.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("order_info")
public class OrderInfoEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderSn;

    private Long userId;

    private String status;

    private BigDecimal totalAmount;

    private String source;

    private String sourceId;

    private LocalDateTime payExpireAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

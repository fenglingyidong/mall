package com.mall.order.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("seckill_order")
public class SeckillOrderEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String reservationId;

    private Long activityId;

    private Long userId;

    private Long skuId;

    private String orderSn;

    private Long bucketShardKey;

    private LocalDateTime createdAt;
}

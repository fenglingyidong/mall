package com.mall.seckill.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("seckill_stock_change_log")
public class SeckillStockChangeLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String requestId;

    private Long activityId;

    private Long skuId;

    private Long bucketId;

    private Integer bucketNo;

    private Long bucketShardKey;

    private String changeType;

    private Integer quantityDelta;

    private Integer afterQuantity;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

package com.mall.seckill.pojo.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("seckill_stock_snapshot")
public class SeckillStockSnapshotEntity {

    @TableId
    private String requestId;

    private Long stockId;

    private Long bucketId;

    private Integer bucketNo;

    private Long bucketShardKey;

    private Long strategyVersion;

    private Long changeId;

    private Long activityId;

    private Long skuId;

    private Long userId;

    private Long activeKey;

    private Integer quantity;

    private String status;

    private String orderSn;

    private String message;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

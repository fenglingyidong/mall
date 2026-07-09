package com.mall.seckill.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("seckill_stock_bucket")
public class SeckillStockBucketEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long activityId;

    private Long skuId;

    private Integer bucketNo;

    private String bucketType;

    private Long shardKey;

    private Integer saleableQuantity;

    private Integer occupyQuantity;

    private Integer settingQuantity;

    private String status;

    private Long version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

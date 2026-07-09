package com.mall.seckill.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("seckill_reservation_guard")
public class SeckillReservationGuardEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String reservationId;

    private String requestId;

    private Long activityId;

    private Long skuId;

    private Long userId;

    private Long guardShardKey;

    private String activeKey;

    private Long bucketId;

    private Integer bucketNo;

    private Long bucketShardKey;

    private String status;

    private String failReason;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

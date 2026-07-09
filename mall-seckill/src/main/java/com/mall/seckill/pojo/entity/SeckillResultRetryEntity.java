package com.mall.seckill.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("seckill_result_retry")
public class SeckillResultRetryEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String messageId;

    private String reservationId;

    private String resultType;

    private String payload;

    private Long bucketShardKey;

    private Integer retryCount;

    private LocalDateTime firstFailedAt;

    private LocalDateTime lastFailedAt;

    private String lastError;

    private LocalDateTime nextRetryAt;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

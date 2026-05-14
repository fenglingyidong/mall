package com.mall.seckill.pojo.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("seckill_result")
public class SeckillResultEntity {

    @TableId
    private String requestId;

    private String status;

    private String orderSn;

    private String message;

    private LocalDateTime updatedAt;
}

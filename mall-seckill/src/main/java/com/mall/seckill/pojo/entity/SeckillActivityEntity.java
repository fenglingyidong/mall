package com.mall.seckill.pojo.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("seckill_activity")
public class SeckillActivityEntity {

    @TableId
    private Long id;

    private String name;

    private LocalDateTime startAt;

    private LocalDateTime endAt;
}

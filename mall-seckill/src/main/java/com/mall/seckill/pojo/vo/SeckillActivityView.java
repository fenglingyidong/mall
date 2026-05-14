package com.mall.seckill.pojo.vo;

import com.mall.seckill.pojo.entity.SeckillSku;

import java.time.Instant;
import java.util.List;

public record SeckillActivityView(Long activityId, String activityName, Instant startAt, Instant endAt, List<SeckillSku> skus) {
}

package com.mall.seckill.pojo.entity;

import java.time.Instant;

public record SeckillActivity(Long activityId, String activityName, Instant startAt, Instant endAt) {

    public boolean activeAt(Instant now) {
        return !now.isBefore(startAt) && !now.isAfter(endAt);
    }
}



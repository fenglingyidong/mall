package com.mall.seckill.service.impl;

import com.mall.seckill.config.SeckillProperties;
import org.springframework.stereotype.Component;

@Component
public class SeckillBucketShardRouter {

    private final SeckillProperties properties;

    public SeckillBucketShardRouter(SeckillProperties properties) {
        this.properties = properties == null ? new SeckillProperties() : properties;
    }

    public long bucketShardKey(Integer bucketNo) {
        if (bucketNo == null || bucketNo <= 0) {
            throw new IllegalArgumentException("bucketNo must be positive");
        }
        return bucketNo.longValue();
    }

    public long centerShardKey() {
        return 0L;
    }

    public String physicalShardTag(Long bucketShardKey) {
        long shardKey = bucketShardKey == null ? centerShardKey() : bucketShardKey;
        int shardCount = Math.max(1, properties.getBucket().getRouting().getPhysicalShardCount());
        return String.valueOf(Math.floorMod(shardKey, shardCount));
    }
}

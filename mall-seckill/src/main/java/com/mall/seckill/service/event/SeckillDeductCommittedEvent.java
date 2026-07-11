package com.mall.seckill.service.event;

public record SeckillDeductCommittedEvent(String requestId, Long bucketShardKey) {
}

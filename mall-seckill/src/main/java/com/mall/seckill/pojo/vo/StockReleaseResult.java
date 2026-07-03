package com.mall.seckill.pojo.vo;

import com.mall.seckill.mapper.SeckillRepository.StockSnapshot;

public record StockReleaseResult(StockSnapshot snapshot, StockVersion stockVersion) {
}

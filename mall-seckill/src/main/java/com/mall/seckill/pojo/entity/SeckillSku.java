package com.mall.seckill.pojo.entity;

import java.math.BigDecimal;

public record SeckillSku(Long id, Long activityId, Long skuId, String skuName, BigDecimal price, Integer stock) {
}



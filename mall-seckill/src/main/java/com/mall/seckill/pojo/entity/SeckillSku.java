package com.mall.seckill.pojo.entity;

import java.math.BigDecimal;

public record SeckillSku(Long activityId, Long skuId, String skuName, BigDecimal price, Integer stock) {
}



package com.mall.seckill.mapper;

public class SeckillStockNotEnoughException extends RuntimeException {

    public SeckillStockNotEnoughException() {
        super("Seckill stock not enough");
    }
}

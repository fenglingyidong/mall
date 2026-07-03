package com.mall.seckill.pojo.vo;

public record StockDeductionResult(int code, StockVersion stockVersion) {

    public static StockDeductionResult success(StockVersion stockVersion) {
        return new StockDeductionResult(0, stockVersion);
    }

    public static StockDeductionResult duplicate() {
        return new StockDeductionResult(2, null);
    }
}

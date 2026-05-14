package com.mall.message;

public record SeckillOrderResultMessage(String requestId, String status, String orderSn, String message) {

    public static SeckillOrderResultMessage success(String requestId, String orderSn) {
        return new SeckillOrderResultMessage(requestId, "SUCCESS", orderSn, "Success");
    }

    public static SeckillOrderResultMessage failed(String requestId, String message) {
        return new SeckillOrderResultMessage(requestId, "FAILED", null, message);
    }
}

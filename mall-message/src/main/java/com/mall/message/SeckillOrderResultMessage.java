package com.mall.message;

public record SeckillOrderResultMessage(String requestId,
                                        String reservationId,
                                        String status,
                                        String orderSn,
                                        String message,
                                        Long bucketShardKey) {

    public SeckillOrderResultMessage(String requestId, String status, String orderSn, String message) {
        this(requestId, requestId, status, orderSn, message, null);
    }

    public SeckillOrderResultMessage(String requestId, String status, String orderSn, String message, Long bucketShardKey) {
        this(requestId, requestId, status, orderSn, message, bucketShardKey);
    }

    public SeckillOrderResultMessage {
        if (reservationId == null || reservationId.isBlank()) {
            reservationId = requestId;
        }
    }

    public static SeckillOrderResultMessage success(String requestId, String orderSn) {
        return success(requestId, null, orderSn);
    }

    public static SeckillOrderResultMessage success(String requestId, Long bucketShardKey, String orderSn) {
        return success(requestId, requestId, bucketShardKey, orderSn);
    }

    public static SeckillOrderResultMessage success(String requestId, String reservationId, Long bucketShardKey, String orderSn) {
        return new SeckillOrderResultMessage(requestId, reservationId, "SUCCESS", orderSn, "Success", bucketShardKey);
    }

    public static SeckillOrderResultMessage failed(String requestId, String message) {
        return failed(requestId, null, message);
    }

    public static SeckillOrderResultMessage failed(String requestId, Long bucketShardKey, String message) {
        return failed(requestId, requestId, bucketShardKey, message);
    }

    public static SeckillOrderResultMessage failed(String requestId, String reservationId, Long bucketShardKey, String message) {
        return new SeckillOrderResultMessage(requestId, reservationId, "FAILED", null, message, bucketShardKey);
    }

    public static SeckillOrderResultMessage canceled(String requestId, String reservationId, Long bucketShardKey, String message) {
        return new SeckillOrderResultMessage(requestId, reservationId, "CANCELED", null, message, bucketShardKey);
    }
}

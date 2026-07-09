package com.mall.message;

public final class MessageNames {

    public static final String MALL_EXCHANGE = "mall.exchange";
    public static final String MALL_DELAY_EXCHANGE = "mall.delay.exchange";
    public static final String MALL_DLX = "mall.dlx";

    public static final String ORDER_CLOSE_DELAY_QUEUE = "mall.order.close.delay.queue";
    public static final String ORDER_CLOSE_QUEUE = "mall.order.close.queue";
    public static final String ORDER_CLOSE_DLQ = "mall.order.close.dlq";

    public static final String SECKILL_ORDER_CREATE_QUEUE = "mall.seckill.order.create.queue";
    public static final String SECKILL_ORDER_CREATE_DLQ = "mall.seckill.order.create.dlq";
    public static final String SECKILL_ORDER_RESULT_QUEUE = "mall.seckill.order.result.queue";
    public static final String SECKILL_ORDER_RESULT_RETRY_DELAY_QUEUE = "mall.seckill.order.result.retry.delay.queue";
    public static final String SECKILL_ORDER_RESULT_DLQ = "mall.seckill.order.result.dlq";

    public static final String ORDER_CLOSE_DELAY_ROUTING_KEY = "order.close.delay";
    public static final String ORDER_CLOSE_ROUTING_KEY = "order.close";
    public static final String ORDER_CLOSE_DLQ_ROUTING_KEY = "order.close.dlq";

    public static final String SECKILL_ORDER_CREATE_ROUTING_KEY = "seckill.order.create";
    public static final String SECKILL_ORDER_CREATE_DLQ_ROUTING_KEY = "seckill.order.create.dlq";
    public static final String SECKILL_ORDER_RESULT_ROUTING_KEY = "seckill.order.result";
    public static final String SECKILL_ORDER_RESULT_RETRY_DELAY_ROUTING_KEY = "seckill.order.result.retry.delay";
    public static final String SECKILL_ORDER_RESULT_DLQ_ROUTING_KEY = "seckill.order.result.dlq";

    private MessageNames() {
    }
}

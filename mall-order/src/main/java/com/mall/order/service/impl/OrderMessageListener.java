package com.mall.order.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.exception.BusinessException;
import com.mall.common.util.JsonUtils;
import com.mall.message.MessageNames;
import com.mall.message.ReliableMessageRepository;
import com.mall.message.ReliableMessagePublisher;
import com.mall.message.SeckillOrderResultMessage;
import com.mall.order.pojo.dto.SeckillOrderRequest;
import com.mall.order.pojo.entity.OrderInfo;
import com.mall.order.service.OrderService;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class OrderMessageListener {

    private static final String BUCKET_SHARD_KEY_HEADER = "bucketShardKey";

    @Autowired
    private OrderService orderService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ReliableMessagePublisher messagePublisher;
    @Autowired
    private ReliableMessageRepository messageRepository;

    private final Timer seckillOrderCreateListenerTimer;

    public OrderMessageListener() {
        this(new SimpleMeterRegistry());
    }

    @Autowired
    public OrderMessageListener(ObjectProvider<MeterRegistry> meterRegistry) {
        this(meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
    }

    private OrderMessageListener(MeterRegistry meterRegistry) {
        MeterRegistry registry = meterRegistry == null ? new SimpleMeterRegistry() : meterRegistry;
        this.seckillOrderCreateListenerTimer = Timer.builder("mall.order.seckill.create.listener")
                .description("Latency from receiving seckill create message to RabbitMQ ack or nack")
                .register(registry);
    }

    @RabbitListener(
            queues = MessageNames.ORDER_CLOSE_QUEUE,
            containerFactory = "orderCloseRabbitListenerContainerFactory")
    public void onOrderClose(String orderSn, Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            orderService.closeIfCreated(orderSn);
            markConsumed(message, null);
            channel.basicAck(deliveryTag, false);
        } catch (Exception exception) {
            channel.basicNack(deliveryTag, false, false);
        }
    }

    @RabbitListener(
            queues = MessageNames.SECKILL_ORDER_CREATE_QUEUE,
            containerFactory = "seckillOrderCreateRabbitListenerContainerFactory")
    public void onSeckillOrderCreate(String payload, Message message, Channel channel) throws IOException {
        Timer.Sample sample = Timer.start();
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        SeckillOrderRequest request = null;
        Long bucketShardKey = bucketShardKeyHeader(message);
        try {
            request = objectMapper.readValue(payload, SeckillOrderRequest.class);
            bucketShardKey = resolveBucketShardKey(request, message);
            orderService.createSeckillOrder(request);
            channel.basicAck(deliveryTag, false);
        } catch (Exception exception) {
            if (isRetryableCreateFailure(exception)) {
                channel.basicNack(deliveryTag, false, true);
                return;
            }
            if (request != null) {
                publishSeckillResult(SeckillOrderResultMessage.failed(
                        request.requestId(),
                        request.reservationId(),
                        bucketShardKey,
                        exception.getMessage()));
            }
            channel.basicNack(deliveryTag, false, false);
        } finally {
            sample.stop(seckillOrderCreateListenerTimer);
        }
    }

@RabbitListener(queues = {
        MessageNames.ORDER_CLOSE_DLQ,
        MessageNames.SECKILL_ORDER_CREATE_DLQ
})
    public void onDeadLetter(String payload, Message message, Channel channel) throws IOException {
        channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
    }

    private void publishSeckillResult(SeckillOrderResultMessage resultMessage) {
        String payload = JsonUtils.toJson(objectMapper, resultMessage);
        messagePublisher.publishSeckillOrderResult(resultMessage.requestId(), payload, resultMessage.bucketShardKey());
    }

    private void markConsumed(Message message, Long bucketShardKey) {
        String messageId = message.getMessageProperties().getMessageId();
        if (messageId != null) {
            messageRepository.markConsumed(messageId, bucketShardKey);
        }
    }

    private Long resolveBucketShardKey(SeckillOrderRequest request, Message message) {
        if (request != null && request.bucketShardKey() != null) {
            return request.bucketShardKey();
        }
        return bucketShardKeyHeader(message);
    }

    private Long bucketShardKeyHeader(Message message) {
        Object rawValue = message.getMessageProperties().getHeaders().get(BUCKET_SHARD_KEY_HEADER);
        if (rawValue instanceof Number number) {
            return number.longValue();
        }
        if (rawValue instanceof String text && !text.isBlank()) {
            try {
                return Long.valueOf(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private boolean isRetryableCreateFailure(Exception exception) {
        if (exception instanceof BusinessException businessException) {
            return businessException.code() == 404 || businessException.code() == 409;
        }
        return false;
    }
}

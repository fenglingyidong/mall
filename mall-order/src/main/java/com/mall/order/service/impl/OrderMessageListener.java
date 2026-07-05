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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class OrderMessageListener {

    @Autowired
    private OrderService orderService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ReliableMessagePublisher messagePublisher;
    @Autowired
    private ReliableMessageRepository messageRepository;

    @RabbitListener(queues = MessageNames.ORDER_CLOSE_QUEUE)
    public void onOrderClose(String orderSn, Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            orderService.closeIfCreated(orderSn);
            markConsumed(message);
            channel.basicAck(deliveryTag, false);
        } catch (Exception exception) {
            channel.basicNack(deliveryTag, false, false);
        }
    }

    @RabbitListener(queues = MessageNames.SECKILL_ORDER_CREATE_QUEUE)
    public void onSeckillOrderCreate(String payload, Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        SeckillOrderRequest request = null;
        try {
            request = objectMapper.readValue(payload, SeckillOrderRequest.class);
            OrderInfo order = orderService.createSeckillOrder(request);
            publishSeckillResult(SeckillOrderResultMessage.success(request.requestId(), order.orderSn()));
            markConsumed(message);
            channel.basicAck(deliveryTag, false);
        } catch (Exception exception) {
            if (isRetryableSeckillOrderCreateFailure(exception)) {
                channel.basicNack(deliveryTag, false, true);
                return;
            }
            if (request != null) {
                publishSeckillResult(SeckillOrderResultMessage.failed(request.requestId(), exception.getMessage()));
            }
            channel.basicNack(deliveryTag, false, false);
        }
    }

    @RabbitListener(queues = {
            MessageNames.ORDER_CLOSE_DLQ,
            MessageNames.SECKILL_ORDER_CREATE_DLQ,
            MessageNames.SECKILL_ORDER_RESULT_DLQ
    })
    public void onDeadLetter(String payload, Message message, Channel channel) throws IOException {
        channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
    }

    private void publishSeckillResult(SeckillOrderResultMessage resultMessage) {
        String payload = JsonUtils.toJson(objectMapper, resultMessage);
        messagePublisher.publishSeckillOrderResult(resultMessage.requestId(), payload);
    }

    private void markConsumed(Message message) {
        String messageId = message.getMessageProperties().getMessageId();
        if (messageId != null) {
            messageRepository.markConsumed(messageId);
        }
    }

    private boolean isRetryableSeckillOrderCreateFailure(Exception exception) {
        if (exception instanceof BusinessException businessException) {
            String message = businessException.getMessage();
            return (businessException.code() == 404 && "Order not found".equals(message))
                    || (businessException.code() == 409 && "Seckill message already consumed".equals(message));
        }
        return false;
    }
}

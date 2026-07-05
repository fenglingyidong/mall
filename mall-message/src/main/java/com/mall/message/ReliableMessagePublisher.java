package com.mall.message;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.TimeUnit;

@Component
public class ReliableMessagePublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ReliableMessageRepository repository;
    private final TaskExecutor dispatchExecutor;

    public ReliableMessagePublisher(RabbitTemplate rabbitTemplate,
                                    ReliableMessageRepository repository,
                                    @Qualifier("reliableMessageDispatchExecutor") TaskExecutor dispatchExecutor) {
        this.rabbitTemplate = rabbitTemplate;
        this.repository = repository;
        this.dispatchExecutor = dispatchExecutor;
        this.rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (correlationData == null) {
                return;
            }
            if (ack) {
                repository.markSent(correlationData.getId());
            } else {
                repository.markFailed(correlationData.getId(), cause == null ? "RabbitMQ nack" : cause);
            }
        });
        this.rabbitTemplate.setReturnsCallback(returned -> {
            Message message = returned.getMessage();
            String messageId = message.getMessageProperties().getMessageId();
            if (messageId != null) {
                repository.markFailed(messageId, returned.getReplyText());
            }
        });
    }

    public ReliableMessage publishSeckillOrderCreate(String businessKey, String payload) {
        return publish(MessageNames.MALL_EXCHANGE, MessageNames.SECKILL_ORDER_CREATE_ROUTING_KEY, businessKey, payload, null);
    }

    public ReliableMessage enqueueSeckillOrderCreate(String businessKey, String payload) {
        return enqueue(MessageNames.MALL_EXCHANGE, MessageNames.SECKILL_ORDER_CREATE_ROUTING_KEY, businessKey, payload, null);
    }

    public ReliableMessage publishSeckillOrderResult(String requestId, String payload) {
        return publish(MessageNames.MALL_EXCHANGE, MessageNames.SECKILL_ORDER_RESULT_ROUTING_KEY, requestId, payload, null);
    }

    public ReliableMessage publishOrderCloseDelay(String orderSn, long delay, TimeUnit unit) {
        long delayMillis = Math.max(1, unit.toMillis(delay));
        return publish(MessageNames.MALL_DELAY_EXCHANGE, MessageNames.ORDER_CLOSE_DELAY_ROUTING_KEY, orderSn, orderSn, delayMillis);
    }

    public void resend(ReliableMessage message) {
        send(message);
    }

    private ReliableMessage publish(String exchange, String routingKey, String businessKey, String payload, Long delayMillis) {
        ReliableMessage message = ReliableMessage.of(exchange, routingKey, businessKey, payload, delayMillis);
        repository.save(message);
        send(message);
        return message;
    }

    private ReliableMessage enqueue(String exchange, String routingKey, String businessKey, String payload, Long delayMillis) {
        ReliableMessage message = ReliableMessage.of(exchange, routingKey, businessKey, payload, delayMillis);
        repository.save(message);
        dispatchAfterCommit(message);
        return message;
    }

    private void dispatchAfterCommit(ReliableMessage message) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            dispatchAsync(message);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatchAsync(message);
            }
        });
    }

    private void dispatchAsync(ReliableMessage message) {
        dispatchExecutor.execute(() -> send(message));
    }

    private void send(ReliableMessage message) {
        MessagePostProcessor postProcessor = rabbitMessage -> {
            rabbitMessage.getMessageProperties().setMessageId(message.messageId());
            rabbitMessage.getMessageProperties().setHeader("businessKey", message.businessKey());
            rabbitMessage.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            if (message.delayMillis() != null) {
                rabbitMessage.getMessageProperties().setExpiration(String.valueOf(message.delayMillis()));
            }
            return rabbitMessage;
        };
        rabbitTemplate.convertAndSend(message.exchange(), message.routingKey(), message.payload(), postProcessor, new CorrelationData(message.messageId()));
    }
}

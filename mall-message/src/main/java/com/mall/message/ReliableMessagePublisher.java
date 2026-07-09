package com.mall.message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.concurrent.TimeUnit;

@Component
public class ReliableMessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(ReliableMessagePublisher.class);

    private static final String BUCKET_SHARD_KEY_HEADER = "bucketShardKey";
    private static final String CORRELATION_SEPARATOR = "@";

    private final RabbitTemplate rabbitTemplate;
    private final ReliableMessageRepository repository;
    private final TaskExecutor dispatchExecutor;
    private final MeterRegistry meterRegistry;
    private final Timer enqueueTimer;
    private final Timer afterCommitDispatchTimer;
    private final Timer sendTimer;

    public ReliableMessagePublisher(RabbitTemplate rabbitTemplate,
                                    ReliableMessageRepository repository,
                                    @Qualifier("reliableMessageDispatchExecutor") TaskExecutor dispatchExecutor) {
        this(rabbitTemplate, repository, dispatchExecutor, new SimpleMeterRegistry());
    }

    @Autowired
    public ReliableMessagePublisher(RabbitTemplate rabbitTemplate,
                                    ReliableMessageRepository repository,
                                    @Qualifier("reliableMessageDispatchExecutor") TaskExecutor dispatchExecutor,
                                    ObjectProvider<MeterRegistry> meterRegistry) {
        this(rabbitTemplate, repository, dispatchExecutor, meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
    }

    private ReliableMessagePublisher(RabbitTemplate rabbitTemplate,
                                     ReliableMessageRepository repository,
                                     TaskExecutor dispatchExecutor,
                                     MeterRegistry meterRegistry) {
        this.rabbitTemplate = rabbitTemplate;
        this.repository = repository;
        this.dispatchExecutor = dispatchExecutor;
        this.meterRegistry = meterRegistry == null ? new SimpleMeterRegistry() : meterRegistry;
        this.enqueueTimer = timer("seckill.submit.record.mq.enqueue", "Reliable message enqueue latency in submit transaction");
        this.afterCommitDispatchTimer = timer("reliable.message.after-commit.dispatch", "Reliable message afterCommit async dispatch latency");
        this.sendTimer = timer("reliable.message.mq.send", "Reliable message RabbitMQ convertAndSend latency");
        this.rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (correlationData == null) {
                return;
            }
            MessageRouteRef routeRef = decodeCorrelationId(correlationData.getId());
            if (ack) {
                repository.markSentIfDispatching(routeRef.messageId(), routeRef.bucketShardKey());
            } else {
                repository.markFailedIfDispatching(routeRef.messageId(),
                        routeRef.bucketShardKey(),
                        MessageErrorType.CONFIRM_NACK,
                        cause == null ? "RabbitMQ nack" : cause);
            }
        });
        this.rabbitTemplate.setReturnsCallback(returned -> {
            Message message = returned.getMessage();
            String messageId = message.getMessageProperties().getMessageId();
            if (messageId != null) {
                repository.markFailedIfDispatching(messageId,
                        bucketShardKeyHeader(message),
                        MessageErrorType.RETURNED,
                        returned.getReplyText());
            }
        });
    }

    public ReliableMessage publishSeckillOrderCreate(String businessKey, String payload) {
        return publish(MessageNames.MALL_EXCHANGE, MessageNames.SECKILL_ORDER_CREATE_ROUTING_KEY, businessKey, payload, null);
    }

    public ReliableMessage enqueueSeckillOrderCreate(String businessKey, String payload) {
        return enqueue(MessageNames.MALL_EXCHANGE, MessageNames.SECKILL_ORDER_CREATE_ROUTING_KEY, businessKey, payload, null);
    }

    public ReliableMessage enqueueSeckillOrderCreate(String businessKey, String payload, Long bucketShardKey) {
        return enqueue(MessageNames.MALL_EXCHANGE, MessageNames.SECKILL_ORDER_CREATE_ROUTING_KEY,
                businessKey, payload, bucketShardKey, null);
    }

    public ReliableMessage publishSeckillOrderResult(String requestId, String payload) {
        return publish(MessageNames.MALL_EXCHANGE, MessageNames.SECKILL_ORDER_RESULT_ROUTING_KEY, requestId, payload, null);
    }

    public ReliableMessage publishSeckillOrderResult(String requestId, String payload, Long bucketShardKey) {
        return publish(MessageNames.MALL_EXCHANGE, MessageNames.SECKILL_ORDER_RESULT_ROUTING_KEY,
                requestId, payload, bucketShardKey, null);
    }

    public ReliableMessage publishSeckillOrderResultRetry(String requestId, String payload, Long bucketShardKey, long delayMillis) {
        return publish(MessageNames.MALL_DELAY_EXCHANGE, MessageNames.SECKILL_ORDER_RESULT_RETRY_DELAY_ROUTING_KEY,
                requestId, payload, bucketShardKey, Math.max(1L, delayMillis));
    }

    public ReliableMessage enqueueSeckillOrderResult(String requestId, String payload, Long bucketShardKey) {
        return enqueue(MessageNames.MALL_EXCHANGE, MessageNames.SECKILL_ORDER_RESULT_ROUTING_KEY,
                requestId, payload, bucketShardKey, null);
    }

    public ReliableMessage publishOrderCloseDelay(String orderSn, long delay, TimeUnit unit) {
        long delayMillis = Math.max(1, unit.toMillis(delay));
        return publish(MessageNames.MALL_DELAY_EXCHANGE, MessageNames.ORDER_CLOSE_DELAY_ROUTING_KEY, orderSn, orderSn, delayMillis);
    }

    public void resend(ReliableMessage message) {
        send(message);
    }

    private ReliableMessage publish(String exchange, String routingKey, String businessKey, String payload, Long delayMillis) {
        return publish(exchange, routingKey, businessKey, payload, null, delayMillis);
    }

    private ReliableMessage publish(String exchange,
                                    String routingKey,
                                    String businessKey,
                                    String payload,
                                    Long bucketShardKey,
                                    Long delayMillis) {
        ReliableMessage message = ReliableMessage.of(exchange, routingKey, businessKey, payload, bucketShardKey, delayMillis);
        repository.save(message);
        send(message);
        return message;
    }

    private ReliableMessage enqueue(String exchange, String routingKey, String businessKey, String payload, Long delayMillis) {
        return enqueue(exchange, routingKey, businessKey, payload, null, delayMillis);
    }

    private ReliableMessage enqueue(String exchange,
                                    String routingKey,
                                    String businessKey,
                                    String payload,
                                    Long bucketShardKey,
                                    Long delayMillis) {
        return enqueueTimer.record(() -> {
            ReliableMessage message = ReliableMessage.of(exchange, routingKey, businessKey, payload, bucketShardKey, delayMillis);
            repository.save(message);
            dispatchAfterCommit(message);
            return message;
        });
    }

    private void dispatchAfterCommit(ReliableMessage message) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            afterCommitDispatchTimer.record(() -> dispatchAsync(message));
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                afterCommitDispatchTimer.record(() -> dispatchAsync(message));
            }
        });
    }

    private void dispatchAsync(ReliableMessage message) {
        try {
            dispatchExecutor.execute(() -> send(message));
        } catch (TaskRejectedException exception) {
            log.warn("Reliable message dispatch rejected, messageId={}, businessKey={}, routingKey={}, bucketShardKey={}",
                    message.messageId(), message.businessKey(), message.routingKey(), message.bucketShardKey(), exception);
        } catch (RuntimeException exception) {
            log.warn("Reliable message dispatch scheduling failed, messageId={}, businessKey={}, routingKey={}, bucketShardKey={}",
                    message.messageId(), message.businessKey(), message.routingKey(), message.bucketShardKey(), exception);
        }
    }

    private void send(ReliableMessage message) {
        sendTimer.record(() -> {
            if (!repository.markDispatching(message.messageId(), message.bucketShardKey())) {
                return;
            }
            MessagePostProcessor postProcessor = rabbitMessage -> {
                rabbitMessage.getMessageProperties().setMessageId(message.messageId());
                rabbitMessage.getMessageProperties().setHeader("businessKey", message.businessKey());
                if (message.bucketShardKey() != null) {
                    rabbitMessage.getMessageProperties().setHeader(BUCKET_SHARD_KEY_HEADER, message.bucketShardKey());
                }
                rabbitMessage.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                if (message.delayMillis() != null) {
                    rabbitMessage.getMessageProperties().setExpiration(String.valueOf(message.delayMillis()));
                }
                return rabbitMessage;
            };
            try {
                rabbitTemplate.convertAndSend(message.exchange(), message.routingKey(), message.payload(), postProcessor,
                        new CorrelationData(encodeCorrelationId(message)));
            } catch (RuntimeException exception) {
                repository.markFailedIfDispatching(message.messageId(),
                        message.bucketShardKey(),
                        MessageErrorType.SEND_EXCEPTION,
                        exception.getMessage());
                throw exception;
            }
        });
    }

    private String encodeCorrelationId(ReliableMessage message) {
        if (message.bucketShardKey() == null) {
            return message.messageId();
        }
        return message.bucketShardKey() + CORRELATION_SEPARATOR + message.messageId();
    }

    private MessageRouteRef decodeCorrelationId(String correlationId) {
        if (correlationId == null) {
            return new MessageRouteRef(null, null);
        }
        int separatorIndex = correlationId.indexOf(CORRELATION_SEPARATOR);
        if (separatorIndex <= 0) {
            return new MessageRouteRef(correlationId, null);
        }
        String shardKey = correlationId.substring(0, separatorIndex);
        String messageId = correlationId.substring(separatorIndex + CORRELATION_SEPARATOR.length());
        try {
            return new MessageRouteRef(messageId, Long.valueOf(shardKey));
        } catch (NumberFormatException exception) {
            return new MessageRouteRef(correlationId, null);
        }
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

    private Timer timer(String name, String description) {
        return Timer.builder(name)
                .description(description)
                .register(meterRegistry);
    }

    private record MessageRouteRef(String messageId, Long bucketShardKey) {
    }
}

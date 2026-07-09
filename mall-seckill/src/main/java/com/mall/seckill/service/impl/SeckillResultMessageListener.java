package com.mall.seckill.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.message.MessageNames;
import com.mall.message.ReliableMessagePublisher;
import com.mall.message.ReliableMessageRepository;
import com.mall.message.SeckillOrderResultMessage;
import com.mall.common.exception.BusinessException;
import com.mall.seckill.cache.SeckillStockCache;
import com.mall.seckill.config.SeckillProperties;
import com.mall.seckill.mapper.ReservationGuardRepository;
import com.mall.seckill.mapper.SeckillRepository;
import com.mall.seckill.mapper.SeckillResultRetryRepository;
import com.mall.seckill.pojo.vo.SeckillResult;
import com.mall.seckill.pojo.vo.StockReleaseResult;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Component
public class SeckillResultMessageListener {

    private static final Logger log = LoggerFactory.getLogger(SeckillResultMessageListener.class);
    private static final String BUCKET_SHARD_KEY_HEADER = "bucketShardKey";

    private final SeckillRepository repository;
    private final SeckillStockCache stockCache;
    private final ObjectMapper objectMapper;
    private final ReliableMessageRepository messageRepository;
    private final ReservationGuardRepository guardRepository;
    private final SeckillResultRetryRepository retryRepository;
    private final ReliableMessagePublisher retryMessagePublisher;
    private final SeckillProperties properties;

    public SeckillResultMessageListener(SeckillRepository repository,
                                        SeckillStockCache stockCache,
                                        ObjectMapper objectMapper,
                                        ReliableMessageRepository messageRepository) {
        this(repository,
                stockCache,
                objectMapper,
                messageRepository,
                (ReservationGuardRepository) null,
                (SeckillResultRetryRepository) null,
                (ReliableMessagePublisher) null,
                new SeckillProperties());
    }

    @Autowired
    public SeckillResultMessageListener(SeckillRepository repository,
                                        SeckillStockCache stockCache,
                                        ObjectMapper objectMapper,
                                        ReliableMessageRepository messageRepository,
                                        ObjectProvider<ReservationGuardRepository> guardRepository,
                                        ObjectProvider<SeckillResultRetryRepository> retryRepository,
                                        ObjectProvider<ReliableMessagePublisher> retryMessagePublisher,
                                        SeckillProperties properties) {
        this(repository,
                stockCache,
                objectMapper,
                messageRepository,
                guardRepository.getIfAvailable(),
                retryRepository.getIfAvailable(),
                retryMessagePublisher.getIfAvailable(),
                properties);
    }

    private SeckillResultMessageListener(SeckillRepository repository,
                                         SeckillStockCache stockCache,
                                         ObjectMapper objectMapper,
                                         ReliableMessageRepository messageRepository,
                                         ReservationGuardRepository guardRepository,
                                         SeckillResultRetryRepository retryRepository,
                                         ReliableMessagePublisher retryMessagePublisher,
                                         SeckillProperties properties) {
        this.repository = repository;
        this.stockCache = stockCache;
        this.objectMapper = objectMapper;
        this.messageRepository = messageRepository;
        this.guardRepository = guardRepository;
        this.retryRepository = retryRepository;
        this.retryMessagePublisher = retryMessagePublisher;
        this.properties = properties == null ? new SeckillProperties() : properties;
    }

    @RabbitListener(
            queues = MessageNames.SECKILL_ORDER_RESULT_QUEUE,
            containerFactory = "seckillOrderResultRabbitListenerContainerFactory")
    public void onSeckillOrderResult(String payload, Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        SeckillOrderResultMessage resultMessage = null;
        Long bucketShardKey = null;
        try {
            resultMessage = objectMapper.readValue(payload, SeckillOrderResultMessage.class);
            bucketShardKey = resolveBucketShardKey(resultMessage, message);
            handleResult(resultMessage, bucketShardKey);
            markConsumed(message, bucketShardKey);
            channel.basicAck(deliveryTag, false);
        } catch (Exception exception) {
            log.warn("Failed to consume seckill order result message", exception);
            if (resultMessage != null && scheduleRetry(payload, message, resultMessage, bucketShardKey, exception)) {
                channel.basicAck(deliveryTag, false);
                return;
            }
            channel.basicNack(deliveryTag, false, false);
        }
    }

    private boolean scheduleRetry(String payload,
                                  Message message,
                                  SeckillOrderResultMessage resultMessage,
                                  Long bucketShardKey,
                                  Exception exception) {
        if (!resultRetryEnabled()) {
            return false;
        }
        SeckillResultRetryRepository.RetryDecision decision = retryRepository.recordFailure(
                retryMessageId(message, resultMessage),
                resultMessage.reservationId(),
                resultMessage.status(),
                payload,
                bucketShardKey,
                exception.getMessage(),
                properties.getResultRetry().getMaxAttempts(),
                retryDelaysMillis());
        if (!decision.shouldRetry()) {
            log.error("Seckill order result retry exceeded, reservationId={}, status={}, retryCount={}",
                    resultMessage.reservationId(), resultMessage.status(), decision.retryCount());
            terminalizeRetryExhausted(resultMessage, exception);
            markConsumed(message, bucketShardKey);
            return true;
        }
        retryMessagePublisher.publishSeckillOrderResultRetry(
                resultMessage.requestId(),
                payload,
                bucketShardKey,
                decision.delayMillis());
        return true;
    }

    private void terminalizeRetryExhausted(SeckillOrderResultMessage resultMessage, Exception exception) {
        String reason = retryExhaustedReason(exception);
        repository.saveResult(new SeckillResult(
                resultMessage.requestId(),
                "FAILED",
                resultMessage.orderSn(),
                reason));
        if (guardRepository == null) {
            return;
        }
        if ("SUCCESS".equals(resultMessage.status())) {
            boolean markedFailed = guardRepository.markFailedIfProcessing(resultMessage.reservationId(), reason);
            if (!markedFailed) {
                guardRepository.markReleased(resultMessage.reservationId(), reason);
            }
        } else {
            guardRepository.markReleased(resultMessage.reservationId(), reason);
        }
    }

    private String retryExhaustedReason(Exception exception) {
        String message = exception == null ? null : exception.getMessage();
        if (message == null || message.isBlank()) {
            return "Result retry exhausted";
        }
        return "Result retry exhausted: " + message;
    }

    private void handleResult(SeckillOrderResultMessage resultMessage, Long bucketShardKey) {
        if ("SUCCESS".equals(resultMessage.status())) {
            SeckillRepository.StockSnapshot snapshot = repository.confirmDeduction(
                    resultMessage.reservationId(),
                    bucketShardKey,
                    resultMessage.orderSn(),
                    resultMessage.message());
            if (snapshot == null) {
                throw new BusinessException(409, "Seckill deduction snapshot missing for success result");
            }
            if (!"CONFIRMED".equals(snapshot.status())) {
                throw new BusinessException(409, "Seckill deduction snapshot is not confirmable: " + snapshot.status());
            }
            if (guardRepository != null) {
                guardRepository.markConfirmed(resultMessage.reservationId());
            }
            repository.saveResult(new SeckillResult(
                    resultMessage.requestId(),
                    resultMessage.status(),
                    resultMessage.orderSn(),
                    resultMessage.message()));
            return;
        }

        if ("ORDER_CLOSED".equals(resultMessage.status())
                || "ORDER_CANCELED".equals(resultMessage.status())
                || "CANCELED".equals(resultMessage.status())) {
            handleOrderClosedOrCanceled(resultMessage, bucketShardKey);
            return;
        }

        handleCreateFailed(resultMessage, bucketShardKey);
    }

    private void handleCreateFailed(SeckillOrderResultMessage resultMessage, Long bucketShardKey) {
        StockReleaseResult releaseResult = repository.releaseDeduction(
                resultMessage.reservationId(),
                bucketShardKey,
                resultMessage.message());
        SeckillRepository.StockSnapshot snapshot = requireReleaseSnapshot(
                releaseResult,
                resultMessage.reservationId(),
                "create failed result");
        if ("RELEASED".equals(snapshot.status())) {
            if (guardRepository != null) {
                guardRepository.markReleasedFromDeducted(resultMessage.reservationId(), resultMessage.message());
            }
            stockCache.refresh(
                    snapshot.activityId(),
                    snapshot.skuId(),
                    releaseResult.stockVersion());
            repository.saveResult(new SeckillResult(
                    resultMessage.requestId(),
                    resultMessage.status(),
                    resultMessage.orderSn(),
                    resultMessage.message()));
            return;
        }
        if ("CONFIRMED".equals(snapshot.status())) {
            log.warn("Skip stale seckill create failed result after confirmation, reservationId={}, bucketShardKey={}",
                    resultMessage.reservationId(), bucketShardKey);
            return;
        }
        throw new BusinessException(409, "Seckill deduction snapshot is not releasable for create failed result: "
                + snapshot.status());
    }

    private void handleOrderClosedOrCanceled(SeckillOrderResultMessage resultMessage, Long bucketShardKey) {
        StockReleaseResult releaseResult = repository.releaseConfirmedDeduction(
                resultMessage.reservationId(),
                bucketShardKey,
                resultMessage.message());
        SeckillRepository.StockSnapshot snapshot = requireReleaseSnapshot(
                releaseResult,
                resultMessage.reservationId(),
                "order closed result");
        if ("RELEASED".equals(snapshot.status())) {
            if (guardRepository != null) {
                guardRepository.markReleasedFromConfirmed(resultMessage.reservationId(), resultMessage.message());
            }
            stockCache.refresh(
                    snapshot.activityId(),
                    snapshot.skuId(),
                    releaseResult.stockVersion());
            repository.saveResult(new SeckillResult(
                    resultMessage.requestId(),
                    resultMessage.status(),
                    resultMessage.orderSn(),
                    resultMessage.message()));
            return;
        }
        throw new BusinessException(409, "Seckill confirmed deduction snapshot is not releasable for order closed result: "
                + snapshot.status());
    }

    private SeckillRepository.StockSnapshot requireReleaseSnapshot(StockReleaseResult releaseResult,
                                                                  String reservationId,
                                                                  String resultType) {
        if (releaseResult == null || releaseResult.snapshot() == null) {
            throw new BusinessException(409, "Seckill deduction snapshot missing for " + resultType
                    + ", reservationId=" + reservationId);
        }
        return releaseResult.snapshot();
    }

    private void markConsumed(Message message, Long bucketShardKey) {
        String messageId = message.getMessageProperties().getMessageId();
        if (messageId != null) {
            messageRepository.markConsumed(messageId, bucketShardKey);
        }
    }

    private Long resolveBucketShardKey(SeckillOrderResultMessage resultMessage, Message message) {
        if (resultMessage.bucketShardKey() != null) {
            return resultMessage.bucketShardKey();
        }
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

    private boolean resultRetryEnabled() {
        return retryRepository != null
                && retryMessagePublisher != null
                && properties.getResultRetry().isEnabled();
    }

    private String retryMessageId(Message message, SeckillOrderResultMessage resultMessage) {
        String messageId = message.getMessageProperties().getMessageId();
        if (messageId != null && !messageId.isBlank()) {
            return messageId;
        }
        return resultMessage.reservationId() + ":" + UUID.randomUUID();
    }

    private List<Long> retryDelaysMillis() {
        return properties.getResultRetry().getDelays().stream()
                .map(this::toMillis)
                .toList();
    }

    private long toMillis(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return 1000L;
        }
        String value = rawValue.trim().toLowerCase(Locale.ROOT);
        try {
            if (value.endsWith("ms")) {
                return Long.parseLong(value.substring(0, value.length() - 2));
            }
            if (value.endsWith("s")) {
                return Long.parseLong(value.substring(0, value.length() - 1)) * 1000L;
            }
            if (value.endsWith("m")) {
                return Long.parseLong(value.substring(0, value.length() - 1)) * 60_000L;
            }
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return 1000L;
        }
    }
}

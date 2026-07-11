package com.mall.message;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

@Repository
public class ReliableMessageRepository {

    private static final ZoneId ZONE_ID = ZoneId.systemDefault();
    private static final int ERROR_MESSAGE_MAX_LENGTH = 512;

    private final MqMessageMapper messageMapper;

    public ReliableMessageRepository(MqMessageMapper messageMapper) {
        this.messageMapper = messageMapper;
    }

    public void save(ReliableMessage message) {
        messageMapper.insert(newEntity(message));
    }

    public void saveIgnoreDuplicates(List<ReliableMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        messageMapper.insertIgnoreBatch(messages.stream()
                .map(this::newEntity)
                .toList());
    }

    public boolean markDispatching(String messageId) {
        return markDispatching(messageId, null);
    }

    public boolean markDispatching(String messageId, Long bucketShardKey) {
        return updateStatus(messageId,
                bucketShardKey,
                List.of(MessageStatus.NEW, MessageStatus.FAILED),
                MessageStatus.DISPATCHING,
                null,
                null) > 0;
    }

    public void markSent(String messageId) {
        markSentIfDispatching(messageId, null);
    }

    public void markSent(String messageId, Long bucketShardKey) {
        markSentIfDispatching(messageId, bucketShardKey);
    }

    public boolean markSentIfDispatching(String messageId, Long bucketShardKey) {
        return updateStatus(messageId,
                bucketShardKey,
                List.of(MessageStatus.DISPATCHING),
                MessageStatus.SENT,
                null,
                null) > 0;
    }

    public void markFailed(String messageId, String error) {
        markFailedIfDispatching(messageId, null, MessageErrorType.SEND_EXCEPTION, error);
    }

    public void markFailed(String messageId, Long bucketShardKey, String error) {
        markFailedIfDispatching(messageId, bucketShardKey, MessageErrorType.SEND_EXCEPTION, error);
    }

    public boolean markFailedIfDispatching(String messageId,
                                           Long bucketShardKey,
                                           MessageErrorType errorType,
                                           String error) {
        return updateStatus(messageId,
                bucketShardKey,
                List.of(MessageStatus.DISPATCHING),
                MessageStatus.FAILED,
                errorType,
                error) > 0;
    }

    public int markDispatchingTimedOut(Instant timeoutBefore, int limit) {
        return markDispatchingTimedOut(timeoutBefore, null, limit);
    }

    public int markDispatchingTimedOut(Instant timeoutBefore, Long bucketShardKey, int limit) {
        int safeLimit = Math.max(1, limit);
        var wrapper = Wrappers.<MqMessageEntity>lambdaUpdate()
                .eq(MqMessageEntity::getStatus, MessageStatus.DISPATCHING.name())
                .lt(MqMessageEntity::getUpdatedAt, toLocalDateTime(timeoutBefore))
                .set(MqMessageEntity::getStatus, MessageStatus.FAILED.name())
                .set(MqMessageEntity::getErrorType, MessageErrorType.TIMEOUT.name())
                .set(MqMessageEntity::getErrorMessage, "Reliable message dispatch timeout")
                .set(MqMessageEntity::getUpdatedAt, LocalDateTime.now())
                .last("LIMIT " + safeLimit);
        if (bucketShardKey != null) {
            wrapper.eq(MqMessageEntity::getBucketShardKey, bucketShardKey);
        }
        return messageMapper.update(null, wrapper);
    }

    public int markDispatchingTimedOut(Instant timeoutBefore, Long bucketShardKey, String routingKey, int limit) {
        int safeLimit = Math.max(1, limit);
        var wrapper = Wrappers.<MqMessageEntity>lambdaUpdate()
                .eq(MqMessageEntity::getStatus, MessageStatus.DISPATCHING.name())
                .eq(MqMessageEntity::getRoutingKey, routingKey)
                .lt(MqMessageEntity::getUpdatedAt, toLocalDateTime(timeoutBefore))
                .set(MqMessageEntity::getStatus, MessageStatus.FAILED.name())
                .set(MqMessageEntity::getErrorType, MessageErrorType.TIMEOUT.name())
                .set(MqMessageEntity::getErrorMessage, "Reliable message dispatch timeout")
                .set(MqMessageEntity::getUpdatedAt, LocalDateTime.now())
                .last("LIMIT " + safeLimit);
        if (bucketShardKey == null) {
            wrapper.isNull(MqMessageEntity::getBucketShardKey);
        } else {
            wrapper.eq(MqMessageEntity::getBucketShardKey, bucketShardKey);
        }
        return messageMapper.update(null, wrapper);
    }

    public void markConsumed(String messageId) {
        update(messageId, MessageStatus.CONSUMED, null);
    }

    public void markConsumed(String messageId, Long bucketShardKey) {
        if (bucketShardKey == null) {
            markConsumed(messageId);
            return;
        }
        update(messageId, bucketShardKey, MessageStatus.CONSUMED, null);
    }

    public Collection<MessageRecord> findAll() {
        return messageMapper.selectList(Wrappers.emptyWrapper()).stream()
                .map(this::toRecord)
                .toList();
    }

    public boolean existsByBusinessKey(String businessKey, Long bucketShardKey) {
        Long count = messageMapper.selectCount(Wrappers.<MqMessageEntity>lambdaQuery()
                .eq(MqMessageEntity::getBusinessKey, businessKey)
                .eq(bucketShardKey != null, MqMessageEntity::getBucketShardKey, bucketShardKey));
        return count != null && count > 0;
    }

    public boolean existsByBusinessKeyAndRoutingKey(String businessKey, String routingKey, Long bucketShardKey) {
        var wrapper = Wrappers.<MqMessageEntity>lambdaQuery()
                .eq(MqMessageEntity::getBusinessKey, businessKey)
                .eq(MqMessageEntity::getRoutingKey, routingKey);
        if (bucketShardKey == null) {
            wrapper.isNull(MqMessageEntity::getBucketShardKey);
        } else {
            wrapper.eq(MqMessageEntity::getBucketShardKey, bucketShardKey);
        }
        Long count = messageMapper.selectCount(wrapper);
        return count != null && count > 0;
    }

    public List<ReliableMessage> findNeedCompensation(int limit) {
        return messageMapper.selectList(Wrappers.<MqMessageEntity>lambdaQuery()
                        .in(MqMessageEntity::getStatus, MessageStatus.NEW.name(), MessageStatus.FAILED.name())
                        .orderByAsc(MqMessageEntity::getUpdatedAt)
                        .last("LIMIT " + limit))
                .stream()
                .map(this::toMessage)
                .toList();
    }

    public List<ReliableMessage> findNeedCompensation(Long bucketShardKey, int limit) {
        if (bucketShardKey == null) {
            return findNeedCompensation(limit);
        }
        return messageMapper.selectList(Wrappers.<MqMessageEntity>lambdaQuery()
                        .eq(MqMessageEntity::getBucketShardKey, bucketShardKey)
                        .in(MqMessageEntity::getStatus, MessageStatus.NEW.name(), MessageStatus.FAILED.name())
                        .orderByAsc(MqMessageEntity::getUpdatedAt)
                        .last("LIMIT " + limit))
                .stream()
                .map(this::toMessage)
                .toList();
    }

    public List<ReliableMessage> findNeedCompensation(Long bucketShardKey, String routingKey, int limit) {
        if (routingKey == null || routingKey.isBlank()) {
            return Collections.emptyList();
        }
        var wrapper = Wrappers.<MqMessageEntity>lambdaQuery()
                .eq(MqMessageEntity::getRoutingKey, routingKey)
                .in(MqMessageEntity::getStatus, MessageStatus.NEW.name(), MessageStatus.FAILED.name())
                .orderByAsc(MqMessageEntity::getUpdatedAt)
                .last("LIMIT " + Math.max(1, limit));
        if (bucketShardKey == null) {
            wrapper.isNull(MqMessageEntity::getBucketShardKey);
        } else {
            wrapper.eq(MqMessageEntity::getBucketShardKey, bucketShardKey);
        }
        return messageMapper.selectList(wrapper).stream()
                .map(this::toMessage)
                .toList();
    }

    private void update(String messageId, MessageStatus status, String error) {
        messageMapper.update(null, Wrappers.<MqMessageEntity>update()
                .eq("message_id", messageId)
                .set("status", status.name())
                .set("error_type", null)
                .set("error_message", truncate(error, ERROR_MESSAGE_MAX_LENGTH))
                .set("updated_at", LocalDateTime.now()));
    }

    private void update(String messageId, Long bucketShardKey, MessageStatus status, String error) {
        messageMapper.update(null, Wrappers.<MqMessageEntity>update()
                .eq("message_id", messageId)
                .eq("bucket_shard_key", bucketShardKey)
                .set("status", status.name())
                .set("error_type", null)
                .set("error_message", truncate(error, ERROR_MESSAGE_MAX_LENGTH))
                .set("updated_at", LocalDateTime.now()));
    }

    private int updateStatus(String messageId,
                             Long bucketShardKey,
                             List<MessageStatus> currentStatuses,
                             MessageStatus nextStatus,
                             MessageErrorType errorType,
                             String error) {
        var wrapper = Wrappers.<MqMessageEntity>update()
                .eq("message_id", messageId)
                .in("status", currentStatuses.stream().map(MessageStatus::name).toList())
                .set("status", nextStatus.name())
                .set("error_type", errorType == null ? null : errorType.name())
                .set("error_message", truncate(error, ERROR_MESSAGE_MAX_LENGTH))
                .set("updated_at", LocalDateTime.now());
        if (bucketShardKey != null) {
            wrapper.eq("bucket_shard_key", bucketShardKey);
        }
        return messageMapper.update(null, wrapper);
    }

    private MessageRecord toRecord(MqMessageEntity entity) {
        return new MessageRecord(toMessage(entity), MessageStatus.valueOf(entity.getStatus()), toInstant(entity.getUpdatedAt()), entity.getErrorMessage());
    }

    private ReliableMessage toMessage(MqMessageEntity entity) {
        return new ReliableMessage(
                entity.getMessageId(),
                entity.getExchangeName(),
                entity.getRoutingKey(),
                entity.getBusinessKey(),
                entity.getPayload(),
                entity.getBucketShardKey(),
                entity.getDelayMillis(),
                toInstant(entity.getCreatedAt())
        );
    }

    private MqMessageEntity newEntity(ReliableMessage message) {
        LocalDateTime now = LocalDateTime.now();
        MqMessageEntity entity = new MqMessageEntity();
        entity.setMessageId(message.messageId());
        entity.setExchangeName(message.exchange());
        entity.setRoutingKey(message.routingKey());
        entity.setBusinessKey(message.businessKey());
        entity.setPayload(message.payload());
        entity.setBucketShardKey(message.bucketShardKey());
        entity.setDelayMillis(message.delayMillis());
        entity.setStatus(MessageStatus.NEW.name());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? Instant.now() : value.atZone(ZONE_ID).toInstant();
    }

    private LocalDateTime toLocalDateTime(Instant value) {
        return (value == null ? Instant.now() : value).atZone(ZONE_ID).toLocalDateTime();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}

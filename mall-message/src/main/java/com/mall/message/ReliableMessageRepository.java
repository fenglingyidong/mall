package com.mall.message;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;

@Repository
public class ReliableMessageRepository {

    private static final ZoneId ZONE_ID = ZoneId.systemDefault();

    private final MqMessageMapper messageMapper;

    public ReliableMessageRepository(MqMessageMapper messageMapper) {
        this.messageMapper = messageMapper;
    }

    public void save(ReliableMessage message) {
        MqMessageEntity entity = new MqMessageEntity();
        entity.setMessageId(message.messageId());
        entity.setExchangeName(message.exchange());
        entity.setRoutingKey(message.routingKey());
        entity.setBusinessKey(message.businessKey());
        entity.setPayload(message.payload());
        entity.setDelayMillis(message.delayMillis());
        entity.setStatus(MessageStatus.NEW.name());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        messageMapper.insert(entity);
    }

    public void markSent(String messageId) {
        update(messageId, MessageStatus.SENT, null);
    }

    public void markFailed(String messageId, String error) {
        update(messageId, MessageStatus.FAILED, error);
    }

    public void markConsumed(String messageId) {
        update(messageId, MessageStatus.CONSUMED, null);
    }

    public Collection<MessageRecord> findAll() {
        return messageMapper.selectList(Wrappers.emptyWrapper()).stream()
                .map(this::toRecord)
                .toList();
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

    private void update(String messageId, MessageStatus status, String error) {
        messageMapper.update(null, Wrappers.<MqMessageEntity>lambdaUpdate()
                .eq(MqMessageEntity::getMessageId, messageId)
                .set(MqMessageEntity::getStatus, status.name())
                .set(MqMessageEntity::getErrorMessage, error)
                .set(MqMessageEntity::getUpdatedAt, LocalDateTime.now()));
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
                entity.getDelayMillis(),
                toInstant(entity.getCreatedAt())
        );
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? Instant.now() : value.atZone(ZONE_ID).toInstant();
    }
}

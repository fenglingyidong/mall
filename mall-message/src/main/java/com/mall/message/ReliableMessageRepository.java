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
        messageMapper.update(null, Wrappers.<MqMessageEntity>update()
                .eq("message_id", messageId)
                .in("status", MessageStatus.NEW.name(), MessageStatus.FAILED.name())
                .set("status", MessageStatus.SENT.name())
                .set("error_message", null)
                .set("updated_at", LocalDateTime.now()));
    }

    public void markFailed(String messageId, String error) {
        messageMapper.update(null, Wrappers.<MqMessageEntity>update()
                .eq("message_id", messageId)
                .ne("status", MessageStatus.CONSUMED.name())
                .set("status", MessageStatus.FAILED.name())
                .set("error_message", error)
                .set("updated_at", LocalDateTime.now()));
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
        messageMapper.update(null, Wrappers.<MqMessageEntity>update()
                .eq("message_id", messageId)
                .set("status", status.name())
                .set("error_message", error)
                .set("updated_at", LocalDateTime.now()));
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

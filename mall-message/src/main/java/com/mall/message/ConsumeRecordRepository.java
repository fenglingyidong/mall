package com.mall.message;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public class ConsumeRecordRepository {

    private final ConsumeRecordMapper consumeRecordMapper;

    public ConsumeRecordRepository(ConsumeRecordMapper consumeRecordMapper) {
        this.consumeRecordMapper = consumeRecordMapper;
    }

    public boolean markIfAbsent(String messageId) {
        ConsumeRecordEntity entity = new ConsumeRecordEntity();
        entity.setMessageId(messageId);
        entity.setConsumedAt(LocalDateTime.now());
        try {
            consumeRecordMapper.insert(entity);
            return true;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    public boolean alreadyConsumed(String messageId) {
        return consumeRecordMapper.selectCount(com.baomidou.mybatisplus.core.toolkit.Wrappers.<ConsumeRecordEntity>lambdaQuery()
                .eq(ConsumeRecordEntity::getMessageId, messageId)) > 0;
    }
}

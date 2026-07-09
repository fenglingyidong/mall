package com.mall.seckill.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.seckill.pojo.entity.SeckillResultRetryEntity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public class SeckillResultRetryRepository {

    public static final String STATUS_RETRYING = "RETRYING";
    public static final String STATUS_DLQ = "DLQ";

    private static final int LAST_ERROR_MAX_LENGTH = 512;

    private final SeckillResultRetryMapper mapper;

    public SeckillResultRetryRepository(SeckillResultRetryMapper mapper) {
        this.mapper = mapper;
    }

    public RetryDecision recordFailure(String messageId,
                                       String reservationId,
                                       String resultType,
                                       String payload,
                                       Long bucketShardKey,
                                       String error,
                                       int maxAttempts,
                                       List<Long> retryDelaysMillis) {
        int safeMaxAttempts = Math.max(0, maxAttempts);
        for (int attempt = 0; attempt < 5; attempt++) {
            SeckillResultRetryEntity existing = findLatest(reservationId, resultType);
            if (existing == null) {
                RetryDecision inserted = tryInsertFirst(
                        messageId,
                        reservationId,
                        resultType,
                        payload,
                        bucketShardKey,
                        error,
                        safeMaxAttempts,
                        retryDelaysMillis);
                if (inserted != null) {
                    return inserted;
                }
                continue;
            }
            RetryDecision updated = tryAdvanceExisting(
                    existing,
                    messageId,
                    payload,
                    bucketShardKey,
                    error,
                    safeMaxAttempts,
                    retryDelaysMillis);
            if (updated != null) {
                return updated;
            }
        }
        throw new IllegalStateException("Seckill result retry state changed concurrently");
    }

    private RetryDecision tryInsertFirst(String messageId,
                                         String reservationId,
                                         String resultType,
                                         String payload,
                                         Long bucketShardKey,
                                         String error,
                                         int maxAttempts,
                                         List<Long> retryDelaysMillis) {
        int retryCount = 1;
        boolean shouldRetry = retryCount <= maxAttempts;
        long delayMillis = shouldRetry ? delayMillis(retryCount, retryDelaysMillis) : 0L;
        LocalDateTime now = LocalDateTime.now();
        SeckillResultRetryEntity entity = new SeckillResultRetryEntity();
        entity.setMessageId(messageId);
        entity.setReservationId(reservationId);
        entity.setResultType(resultType);
        entity.setPayload(payload);
        entity.setBucketShardKey(bucketShardKey);
        entity.setRetryCount(retryCount);
        entity.setFirstFailedAt(now);
        entity.setLastFailedAt(now);
        entity.setLastError(truncate(error));
        entity.setNextRetryAt(shouldRetry ? now.plusNanos(delayMillis * 1_000_000L) : null);
        entity.setStatus(shouldRetry ? STATUS_RETRYING : STATUS_DLQ);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try {
            mapper.insert(entity);
            return new RetryDecision(shouldRetry, retryCount, delayMillis);
        } catch (DuplicateKeyException exception) {
            return null;
        }
    }

    private RetryDecision tryAdvanceExisting(SeckillResultRetryEntity existing,
                                             String messageId,
                                             String payload,
                                             Long bucketShardKey,
                                             String error,
                                             int maxAttempts,
                                             List<Long> retryDelaysMillis) {
        int currentRetryCount = existing.getRetryCount() == null ? 0 : existing.getRetryCount();
        int nextRetryCount = currentRetryCount + 1;
        boolean shouldRetry = nextRetryCount <= maxAttempts;
        long delayMillis = shouldRetry ? delayMillis(nextRetryCount, retryDelaysMillis) : 0L;
        LocalDateTime nextRetryAt = shouldRetry ? LocalDateTime.now().plusNanos(delayMillis * 1_000_000L) : null;
        String status = shouldRetry ? STATUS_RETRYING : STATUS_DLQ;
        int updated = mapper.update(null, Wrappers.<SeckillResultRetryEntity>lambdaUpdate()
                .eq(SeckillResultRetryEntity::getId, existing.getId())
                .eq(SeckillResultRetryEntity::getRetryCount, currentRetryCount)
                .set(SeckillResultRetryEntity::getMessageId, messageId)
                .set(SeckillResultRetryEntity::getPayload, payload)
                .set(SeckillResultRetryEntity::getBucketShardKey, bucketShardKey)
                .set(SeckillResultRetryEntity::getRetryCount, nextRetryCount)
                .set(SeckillResultRetryEntity::getLastFailedAt, LocalDateTime.now())
                .set(SeckillResultRetryEntity::getLastError, truncate(error))
                .set(SeckillResultRetryEntity::getNextRetryAt, nextRetryAt)
                .set(SeckillResultRetryEntity::getStatus, status)
                .set(SeckillResultRetryEntity::getUpdatedAt, LocalDateTime.now()));
        if (updated == 0) {
            return null;
        }
        return new RetryDecision(shouldRetry, nextRetryCount, delayMillis);
    }

    private SeckillResultRetryEntity findLatest(String reservationId, String resultType) {
        return mapper.selectOne(Wrappers.<SeckillResultRetryEntity>lambdaQuery()
                .eq(SeckillResultRetryEntity::getReservationId, reservationId)
                .eq(SeckillResultRetryEntity::getResultType, resultType)
                .orderByDesc(SeckillResultRetryEntity::getUpdatedAt)
                .last("LIMIT 1"));
    }

    private long delayMillis(int retryCount, List<Long> retryDelaysMillis) {
        if (retryDelaysMillis == null || retryDelaysMillis.isEmpty()) {
            return 1000L;
        }
        int index = Math.min(retryCount - 1, retryDelaysMillis.size() - 1);
        return Math.max(1L, retryDelaysMillis.get(index));
    }

    private String truncate(String value) {
        if (value == null || value.length() <= LAST_ERROR_MAX_LENGTH) {
            return value;
        }
        return value.substring(0, LAST_ERROR_MAX_LENGTH);
    }

    public record RetryDecision(boolean shouldRetry,
                                int retryCount,
                                long delayMillis) {
    }
}

package com.mall.message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "mall.message.compensation", name = "enabled", havingValue = "true")
public class MessageCompensationJob {

    private static final Logger log = LoggerFactory.getLogger(MessageCompensationJob.class);

    private final ReliableMessageRepository repository;
    private final ReliableMessagePublisher publisher;
    private final List<Long> bucketShardKeys;
    private final long dispatchingTimeoutSeconds;
    private final int batchSize;

    public MessageCompensationJob(ReliableMessageRepository repository,
                                  ReliableMessagePublisher publisher,
                                  @Value("${mall.message.compensation.bucket-shard-keys:}") String bucketShardKeys,
                                  @Value("${mall.message.compensation.dispatching-timeout-seconds:30}") long dispatchingTimeoutSeconds,
                                  @Value("${mall.message.compensation.batch-size:50}") int batchSize) {
        this.repository = repository;
        this.publisher = publisher;
        this.bucketShardKeys = parseBucketShardKeys(bucketShardKeys);
        this.dispatchingTimeoutSeconds = Math.max(1, dispatchingTimeoutSeconds);
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(fixedDelayString = "${mall.message.compensation.fixed-delay:60000}")
    public void compensate() {
        markDispatchingTimedOut();
        if (bucketShardKeys.isEmpty()) {
            repository.findNeedCompensation(batchSize).forEach(publisher::resend);
            return;
        }
        bucketShardKeys.forEach(bucketShardKey ->
                repository.findNeedCompensation(bucketShardKey, batchSize).forEach(publisher::resend));
    }

    private void markDispatchingTimedOut() {
        if (!bucketShardKeys.isEmpty()) {
            bucketShardKeys.forEach(bucketShardKey -> markDispatchingTimedOut(bucketShardKey));
            return;
        }
        markDispatchingTimedOut(null);
    }

    private void markDispatchingTimedOut(Long bucketShardKey) {
        try {
            if (bucketShardKey == null) {
                repository.markDispatchingTimedOut(Instant.now().minusSeconds(dispatchingTimeoutSeconds), batchSize);
                return;
            }
            repository.markDispatchingTimedOut(
                    Instant.now().minusSeconds(dispatchingTimeoutSeconds),
                    bucketShardKey,
                    batchSize);
        } catch (RuntimeException exception) {
            log.warn("Reliable message dispatching timeout mark failed; continue NEW/FAILED compensation", exception);
        }
    }

    private List<Long> parseBucketShardKeys(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return List.of();
        }
        return Arrays.stream(rawValue.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(Long::valueOf)
                .toList();
    }
}

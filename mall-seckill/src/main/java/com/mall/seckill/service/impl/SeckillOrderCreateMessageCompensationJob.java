package com.mall.seckill.service.impl;

import com.mall.message.MessageNames;
import com.mall.message.ReliableMessagePublisher;
import com.mall.message.ReliableMessageRepository;
import com.mall.seckill.config.SeckillProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(prefix = "mall.seckill.order-outbox", name = "enabled", havingValue = "true")
public class SeckillOrderCreateMessageCompensationJob {

    private static final Logger log = LoggerFactory.getLogger(SeckillOrderCreateMessageCompensationJob.class);

    private final ReliableMessageRepository repository;
    private final ReliableMessagePublisher publisher;
    private final SeckillProperties properties;
    private final AtomicBoolean warnedMissingShardKeys = new AtomicBoolean(false);

    public SeckillOrderCreateMessageCompensationJob(ReliableMessageRepository repository,
                                                    ReliableMessagePublisher publisher,
                                                    SeckillProperties properties) {
        this.repository = repository;
        this.publisher = publisher;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${mall.seckill.order-outbox.message-retry-fixed-delay:1000}",
            scheduler = "seckillOutboxRecoveryScheduler")
    public void compensate() {
        List<Long> shardKeys = properties.getBucket().getRouting().getBucketShardKeys();
        if (shardKeys == null || shardKeys.isEmpty()) {
            if (warnedMissingShardKeys.compareAndSet(false, true)) {
                log.warn("Skip seckill order create message compensation because no bucket shard keys are configured");
            }
            return;
        }
        Instant timeoutBefore = Instant.now()
                .minusSeconds(Math.max(1L, properties.getOrderOutbox().getMessageDispatchTimeoutSeconds()));
        int batchSize = Math.max(1, properties.getOrderOutbox().getBatchSize());
        for (Long shardKey : shardKeys) {
            repository.markDispatchingTimedOut(
                    timeoutBefore,
                    shardKey,
                    MessageNames.SECKILL_ORDER_CREATE_ROUTING_KEY,
                    batchSize);
            repository.findNeedCompensation(
                            shardKey,
                            MessageNames.SECKILL_ORDER_CREATE_ROUTING_KEY,
                            batchSize)
                    .forEach(publisher::resend);
        }
    }
}

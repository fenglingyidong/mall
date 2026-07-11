package com.mall.seckill.service.impl;

import com.mall.seckill.config.SeckillProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class SeckillOrderOutboxCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SeckillOrderOutboxCoordinator.class);

    private final SeckillOrderOutboxFromChangeLogService service;
    private final TaskExecutor executor;
    private final SeckillProperties properties;
    private final ConcurrentHashMap<Long, ShardSlot> slots = new ConcurrentHashMap<>();
    private final AtomicBoolean warnedMissingShardKeys = new AtomicBoolean(false);
    private final Counter signalAcceptedCounter;
    private final Counter signalCoalescedCounter;
    private final Counter signalRejectedCounter;

    @Autowired
    public SeckillOrderOutboxCoordinator(SeckillOrderOutboxFromChangeLogService service,
                                         @Qualifier("seckillOrderOutboxExecutor") TaskExecutor executor,
                                         SeckillProperties properties,
                                         ObjectProvider<MeterRegistry> meterRegistry) {
        this(service, executor, properties, meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
    }

    SeckillOrderOutboxCoordinator(SeckillOrderOutboxFromChangeLogService service,
                                  TaskExecutor executor,
                                  SeckillProperties properties,
                                  MeterRegistry meterRegistry) {
        this.service = service;
        this.executor = executor;
        this.properties = properties;
        MeterRegistry registry = meterRegistry == null ? new SimpleMeterRegistry() : meterRegistry;
        this.signalAcceptedCounter = registry.counter("seckill.outbox.signal.accepted");
        this.signalCoalescedCounter = registry.counter("seckill.outbox.signal.coalesced");
        this.signalRejectedCounter = registry.counter("seckill.outbox.signal.rejected");
    }

    public void signal(Long bucketShardKey) {
        if (bucketShardKey == null) {
            return;
        }
        ShardSlot slot = slots.computeIfAbsent(bucketShardKey, ignored -> new ShardSlot());
        if (slot.dirty.compareAndSet(false, true)) {
            signalAcceptedCounter.increment();
        } else {
            signalCoalescedCounter.increment();
        }
        submitIfIdle(bucketShardKey, slot);
    }

    public void recoverConfiguredShards() {
        List<Long> shardKeys = properties.getBucket().getRouting().getBucketShardKeys();
        if (shardKeys == null || shardKeys.isEmpty()) {
            if (warnedMissingShardKeys.compareAndSet(false, true)) {
                log.warn("Skip seckill outbox shard recovery because no bucket shard keys are configured");
            }
            return;
        }
        for (Long shardKey : shardKeys) {
            service.resetStaleOutboxing(shardKey);
            signal(shardKey);
        }
    }

    private void submitIfIdle(Long bucketShardKey, ShardSlot slot) {
        if (!slot.running.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> run(bucketShardKey, slot));
        } catch (TaskRejectedException exception) {
            slot.running.set(false);
            slot.dirty.set(true);
            signalRejectedCounter.increment();
            log.warn("Rejected seckill outbox shard worker, bucketShardKey={}", bucketShardKey, exception);
        } catch (RuntimeException exception) {
            slot.running.set(false);
            slot.dirty.set(true);
            signalRejectedCounter.increment();
            log.warn("Failed to submit seckill outbox shard worker, bucketShardKey={}", bucketShardKey, exception);
        }
    }

    private void run(Long bucketShardKey, ShardSlot slot) {
        try {
            do {
                slot.dirty.set(false);
                service.drainShard(bucketShardKey, properties.getOrderOutbox().getMaxBatchesPerRun());
            } while (slot.dirty.get());
        } catch (RuntimeException exception) {
            log.warn("Seckill outbox shard worker failed, bucketShardKey={}", bucketShardKey, exception);
        } finally {
            slot.running.set(false);
            if (slot.dirty.get()) {
                submitIfIdle(bucketShardKey, slot);
            }
        }
    }

    private static final class ShardSlot {
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final AtomicBoolean dirty = new AtomicBoolean(false);
    }
}

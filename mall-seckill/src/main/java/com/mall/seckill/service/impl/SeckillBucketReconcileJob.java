package com.mall.seckill.service.impl;

import com.mall.seckill.config.SeckillProperties;
import com.mall.seckill.mapper.SeckillBucketConfigMapper;
import com.mall.seckill.pojo.entity.SeckillBucketConfigEntity;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(prefix = "mall.seckill.bucket.reconcile", name = "enabled", havingValue = "true")
public class SeckillBucketReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(SeckillBucketReconcileJob.class);
    private static final int MAX_BATCH_SIZE = 1000;

    private final SeckillBucketConfigMapper configMapper;
    private final SeckillBucketReconcileService reconcileService;
    private final SeckillProperties properties;
    private final MeterRegistry meterRegistry;
    private final Timer totalTimer;
    private final Counter configCounter;
    private final Counter failureCounter;

    public SeckillBucketReconcileJob(SeckillBucketConfigMapper configMapper,
                                     SeckillBucketReconcileService reconcileService,
                                     SeckillProperties properties,
                                     MeterRegistry meterRegistry) {
        this.configMapper = configMapper;
        this.reconcileService = reconcileService;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.totalTimer = timer(
                "seckill.bucket.reconcile.total",
                "Seckill bucket reconcile scheduled round latency");
        this.configCounter = counter(
                "seckill.bucket.reconcile.config.count",
                "Seckill bucket reconcile scanned config count");
        this.failureCounter = counter(
                "seckill.bucket.reconcile.failure",
                "Seckill bucket reconcile failure count");
    }

    @Scheduled(fixedDelayString = "${mall.seckill.bucket.reconcile.fixed-delay:60000}")
    public void reconcile() {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            List<SeckillBucketConfigEntity> configs = configMapper.selectEnabledForMaintenance(batchSize());
            if (!configs.isEmpty()) {
                configCounter.increment(configs.size());
            }
            for (SeckillBucketConfigEntity config : configs) {
                try {
                    reconcileService.reconcile(config);
                } catch (RuntimeException exception) {
                    failureCounter.increment();
                    log.warn("Failed to reconcile seckill bucket activityId={} skuId={}",
                            config == null ? null : config.getActivityId(),
                            config == null ? null : config.getSkuId(),
                            exception);
                }
            }
        } finally {
            sample.stop(totalTimer);
        }
    }

    private int batchSize() {
        int configured = properties.getBucket().getReconcile().getBatchSize();
        return Math.max(1, Math.min(configured, MAX_BATCH_SIZE));
    }

    private Timer timer(String name, String description) {
        return Timer.builder(name)
                .description(description)
                .register(meterRegistry);
    }

    private Counter counter(String name, String description) {
        return Counter.builder(name)
                .description(description)
                .register(meterRegistry);
    }
}

package com.mall.seckill.service.impl;

import com.mall.seckill.mapper.SeckillBucketConfigMapper;
import com.mall.seckill.mapper.SeckillStockBucketMapper;
import com.mall.seckill.pojo.entity.SeckillBucketConfigEntity;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
public class SeckillBucketReconcileService {

    private final SeckillStockBucketMapper bucketMapper;
    private final SeckillBucketConfigMapper configMapper;
    private final Counter statusFixedCounter;
    private final Counter survivorUpdatedCounter;
    private final Counter invalidConfigCounter;

    public SeckillBucketReconcileService(SeckillStockBucketMapper bucketMapper,
                                         SeckillBucketConfigMapper configMapper,
                                         MeterRegistry meterRegistry) {
        this.bucketMapper = bucketMapper;
        this.configMapper = configMapper;
        this.statusFixedCounter = counter(
                meterRegistry,
                "seckill.bucket.reconcile.status.fixed",
                "Seckill bucket reconcile fixed status count");
        this.survivorUpdatedCounter = counter(
                meterRegistry,
                "seckill.bucket.reconcile.survivor.updated",
                "Seckill bucket reconcile survivor list update count");
        this.invalidConfigCounter = counter(
                meterRegistry,
                "seckill.bucket.reconcile.config.invalid",
                "Seckill bucket reconcile invalid config count");
    }

    @Transactional(rollbackFor = Exception.class)
    public ReconcileResult reconcile(SeckillBucketConfigEntity config) {
        if (config == null
                || config.getId() == null
                || config.getActivityId() == null
                || config.getSkuId() == null) {
            invalidConfigCounter.increment();
            return ReconcileResult.empty();
        }
        int activated = bucketMapper.activatePositiveBuckets(config.getActivityId(), config.getSkuId());
        int emptied = bucketMapper.markActiveNonPositiveBucketsEmpty(config.getActivityId(), config.getSkuId());
        String survivors = formatSurvivors(bucketMapper.selectActivePositiveBucketNos(
                config.getActivityId(),
                config.getSkuId()));
        int survivorUpdated = updateSurvivorsIfChanged(config, survivors);
        recordResult(activated, emptied, survivorUpdated);
        return new ReconcileResult(activated, emptied, survivorUpdated);
    }

    private int updateSurvivorsIfChanged(SeckillBucketConfigEntity config, String survivors) {
        String current = config.getSurvivorBuckets() == null ? "" : config.getSurvivorBuckets().trim();
        if (Objects.equals(current, survivors)) {
            return 0;
        }
        return configMapper.updateSurvivorBuckets(config.getId(), survivors);
    }

    private String formatSurvivors(List<Integer> survivors) {
        if (survivors == null || survivors.isEmpty()) {
            return "";
        }
        return survivors.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    private void recordResult(int activated, int emptied, int survivorUpdated) {
        int statusFixed = activated + emptied;
        if (statusFixed > 0) {
            statusFixedCounter.increment(statusFixed);
        }
        if (survivorUpdated > 0) {
            survivorUpdatedCounter.increment(survivorUpdated);
        }
    }

    private Counter counter(MeterRegistry meterRegistry, String name, String description) {
        return Counter.builder(name)
                .description(description)
                .register(meterRegistry);
    }

    public record ReconcileResult(int activated, int emptied, int survivorUpdated) {

        static ReconcileResult empty() {
            return new ReconcileResult(0, 0, 0);
        }
    }
}

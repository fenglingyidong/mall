package com.mall.seckill.service.impl;

import com.mall.common.exception.BusinessException;
import com.mall.seckill.config.SeckillProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;

@Component
public class SeckillHotspotGuard {

    private final SeckillProperties properties;
    private final ConcurrentMap<String, Semaphore> semaphores = new ConcurrentHashMap<>();

    public SeckillHotspotGuard(SeckillProperties properties) {
        this.properties = properties;
    }

    public boolean isHotspot(Long activityId, Long skuId) {
        if (!hotspotProperties().isEnabled()) {
            return false;
        }
        String key = itemKey(activityId, skuId);
        return hotspotProperties().getItems().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .anyMatch(key::equals);
    }

    public HotspotPermit acquire(Long activityId, Long skuId) {
        if (!isHotspot(activityId, skuId)) {
            return HotspotPermit.noop();
        }
        int maxConcurrent = hotspotProperties().getMaxConcurrent();
        if (maxConcurrent <= 0) {
            throw new BusinessException(429, "Hotspot seckill busy");
        }
        Semaphore semaphore = semaphores.computeIfAbsent(itemKey(activityId, skuId), key -> new Semaphore(maxConcurrent));
        if (!semaphore.tryAcquire()) {
            throw new BusinessException(429, "Hotspot seckill busy");
        }
        return new HotspotPermit(semaphore);
    }

    public List<HotspotItem> hotspotItems() {
        if (!hotspotProperties().isEnabled()) {
            return List.of();
        }
        return hotspotProperties().getItems().stream()
                .map(this::parseItem)
                .filter(Objects::nonNull)
                .toList();
    }

    private HotspotItem parseItem(String rawItem) {
        if (rawItem == null || rawItem.isBlank()) {
            return null;
        }
        String[] parts = rawItem.trim().split(":");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new HotspotItem(Long.valueOf(parts[0]), Long.valueOf(parts[1]));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String itemKey(Long activityId, Long skuId) {
        return activityId + ":" + skuId;
    }

    private SeckillProperties.Hotspot hotspotProperties() {
        return properties.getHotspot();
    }

    public record HotspotItem(Long activityId, Long skuId) {
    }

    public static class HotspotPermit implements AutoCloseable {

        private static final HotspotPermit NOOP = new HotspotPermit(null);

        private final Semaphore semaphore;

        private HotspotPermit(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        private static HotspotPermit noop() {
            return NOOP;
        }

        @Override
        public void close() {
            if (semaphore != null) {
                semaphore.release();
            }
        }
    }
}

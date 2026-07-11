package com.mall.seckill.cache;

import com.mall.seckill.config.SeckillProperties;
import com.mall.seckill.pojo.vo.StockVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SeckillStockCache {

    private static final Logger log = LoggerFactory.getLogger(SeckillStockCache.class);

    private final TairStringCommands tairStringCommands;
    private final SeckillProperties properties;

    public SeckillStockCache(TairStringCommands tairStringCommands, SeckillProperties properties) {
        this.tairStringCommands = tairStringCommands;
        this.properties = properties;
    }

    public void refresh(Long activityId, Long skuId, StockVersion stockVersion) {
        if (!stockCacheProperties().isEnabled() || stockVersion == null || stockVersion.version() == null) {
            return;
        }
        String key = stockKey(activityId, skuId);
        try {
            tairStringCommands.set(key, String.valueOf(stockVersion.stock()), stockVersion.version());
        } catch (RuntimeException exception) {
            log.warn("Failed to refresh seckill stock cache key={}, stock={}, version={}",
                    key, stockVersion.stock(), stockVersion.version(), exception);
        }
    }

    /**
     * 读取 TairString 库存缓存，只用于售罄快速失败。
     *
     * <p>缓存 miss、版本值异常或 Redis/Tair 读取失败都会放行到数据库分桶库存，
     * 数据库扣减仍是库存事实的唯一裁决点。</p>
     */
    public boolean isSoldOut(Long activityId, Long skuId) {
        SeckillProperties.StockCache stockCache = stockCacheProperties();
        if (!stockCache.isEnabled() || !stockCache.isFailFast()) {
            return false;
        }
        String key = stockKey(activityId, skuId);
        try {
            VersionedCacheValue current = tairStringCommands.get(key);
            Integer stock = parseStock(current);
            return stock != null && stock <= 0;
        } catch (RuntimeException exception) {
            log.warn("Failed to read seckill stock cache key={}", key, exception);
            return false;
        }
    }

    String stockKey(Long activityId, Long skuId) {
        return stockCacheProperties().getKeyPrefix() + activityId + ":" + skuId;
    }

    private Integer parseStock(VersionedCacheValue current) {
        if (current == null || current.value() == null || current.value().isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(current.value());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private SeckillProperties.StockCache stockCacheProperties() {
        return properties.getStockCache();
    }
}

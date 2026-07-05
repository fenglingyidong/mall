package com.mall.seckill.cache;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.seckill.config.SeckillProperties;
import com.mall.seckill.mapper.SeckillSkuMapper;
import com.mall.seckill.pojo.entity.SeckillSkuEntity;
import com.mall.seckill.pojo.vo.StockVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(prefix = "mall.seckill.stock-cache.repair", name = "enabled", havingValue = "true")
public class SeckillStockCacheRepairJob {

    private static final Logger log = LoggerFactory.getLogger(SeckillStockCacheRepairJob.class);
    private static final int MAX_REPAIR_LIMIT = 10000;

    private final SeckillSkuMapper skuMapper;
    private final SeckillStockCache stockCache;
    private final SeckillProperties properties;

    public SeckillStockCacheRepairJob(SeckillSkuMapper skuMapper,
                                      SeckillStockCache stockCache,
                                      SeckillProperties properties) {
        this.skuMapper = skuMapper;
        this.stockCache = stockCache;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${mall.seckill.stock-cache.repair.fixed-delay:60000}")
    public void repair() {
        List<SeckillSkuEntity> skus;
        try {
            skus = skuMapper.selectList(Wrappers.<SeckillSkuEntity>lambdaQuery()
                    .orderByAsc(SeckillSkuEntity::getId)
                    .last("LIMIT " + repairLimit()));
        } catch (RuntimeException exception) {
            log.warn("Failed to load seckill SKU stock for cache repair", exception);
            return;
        }

        for (SeckillSkuEntity sku : skus) {
            repairOne(sku);
        }
    }

    private void repairOne(SeckillSkuEntity sku) {
        try {
            stockCache.refresh(
                    sku.getActivityId(),
                    sku.getSkuId(),
                    new StockVersion(sku.getStock(), sku.getVersion()));
        } catch (RuntimeException exception) {
            log.warn("Failed to repair seckill stock cache activityId={}, skuId={}",
                    sku.getActivityId(), sku.getSkuId(), exception);
        }
    }

    private int repairLimit() {
        int configured = properties.getStockCache().getRepair().getLimit();
        return Math.max(1, Math.min(configured, MAX_REPAIR_LIMIT));
    }
}

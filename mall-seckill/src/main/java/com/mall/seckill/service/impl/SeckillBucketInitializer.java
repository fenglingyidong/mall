package com.mall.seckill.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.common.exception.BusinessException;
import com.mall.seckill.mapper.SeckillBucketConfigMapper;
import com.mall.seckill.mapper.SeckillSkuMapper;
import com.mall.seckill.mapper.SeckillStockBucketMapper;
import com.mall.seckill.pojo.entity.SeckillBucketConfigEntity;
import com.mall.seckill.pojo.entity.SeckillSkuEntity;
import com.mall.seckill.pojo.entity.SeckillStockBucketEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Component
public class SeckillBucketInitializer {

    private static final String BUCKET_TYPE_CENTER = "CENTER";
    private static final String BUCKET_TYPE_BUCKET = "BUCKET";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String ROUTE_RANDOM_ACTIVE = "RANDOM_ACTIVE";

    private final SeckillSkuMapper skuMapper;
    private final SeckillBucketConfigMapper configMapper;
    private final SeckillStockBucketMapper bucketMapper;
    private final SeckillBucketShardRouter shardRouter;

    public SeckillBucketInitializer(SeckillSkuMapper skuMapper,
                                    SeckillBucketConfigMapper configMapper,
                                    SeckillStockBucketMapper bucketMapper) {
        this(skuMapper, configMapper, bucketMapper, new SeckillBucketShardRouter(null));
    }

    @Autowired
    public SeckillBucketInitializer(SeckillSkuMapper skuMapper,
                                    SeckillBucketConfigMapper configMapper,
                                    SeckillStockBucketMapper bucketMapper,
                                    SeckillBucketShardRouter shardRouter) {
        this.skuMapper = skuMapper;
        this.configMapper = configMapper;
        this.bucketMapper = bucketMapper;
        this.shardRouter = shardRouter;
    }

    @Transactional(rollbackFor = Exception.class)
    public void initializeFromSku(Long activityId, Long skuId, int bucketCount) {
        if (bucketCount <= 0) {
            throw new BusinessException(400, "Bucket count must be positive");
        }
        if (configMapper.selectEnabled(activityId, skuId) != null) {
            return;
        }
        SeckillSkuEntity sku = skuMapper.selectOne(Wrappers.<SeckillSkuEntity>lambdaQuery()
                .eq(SeckillSkuEntity::getActivityId, activityId)
                .eq(SeckillSkuEntity::getSkuId, skuId));
        if (sku == null) {
            throw new BusinessException(404, "Seckill SKU not found");
        }
        LocalDateTime now = LocalDateTime.now();
        int stock = sku.getStock() == null ? 0 : sku.getStock();
        String survivors = IntStream.rangeClosed(1, bucketCount)
                .filter(bucketNo -> bucketStock(stock, bucketCount, bucketNo) > 0)
                .mapToObj(String::valueOf)
                .collect(Collectors.joining(","));
        SeckillBucketConfigEntity config = new SeckillBucketConfigEntity();
        config.setActivityId(activityId);
        config.setSkuId(skuId);
        config.setBucketCount(bucketCount);
        config.setRouteMode(ROUTE_RANDOM_ACTIVE);
        config.setStatus("ENABLED");
        config.setStrategyVersion(1L);
        config.setSurvivorBuckets(survivors);
        config.setCreatedAt(now);
        config.setUpdatedAt(now);
        configMapper.insert(config);

        bucketMapper.insert(bucket(activityId, skuId, 0, BUCKET_TYPE_CENTER, shardRouter.centerShardKey(),
                stock, 0, stock, STATUS_ACTIVE, now));
        for (int bucketNo = 1; bucketNo <= bucketCount; bucketNo++) {
            int bucketStock = bucketStock(stock, bucketCount, bucketNo);
            bucketMapper.insert(bucket(activityId, skuId, bucketNo, BUCKET_TYPE_BUCKET, shardRouter.bucketShardKey(bucketNo),
                    bucketStock, 0, 0, bucketStock > 0 ? STATUS_ACTIVE : "EMPTY", now));
        }
    }

    private int bucketStock(int stock, int bucketCount, int bucketNo) {
        int base = stock / bucketCount;
        int remainder = stock % bucketCount;
        return base + (bucketNo <= remainder ? 1 : 0);
    }

    private SeckillStockBucketEntity bucket(Long activityId,
                                            Long skuId,
                                            int bucketNo,
                                            String bucketType,
                                            Long shardKey,
                                            int saleableQuantity,
                                            int occupyQuantity,
                                            int settingQuantity,
                                            String status,
                                            LocalDateTime now) {
        SeckillStockBucketEntity bucket = new SeckillStockBucketEntity();
        bucket.setActivityId(activityId);
        bucket.setSkuId(skuId);
        bucket.setBucketNo(bucketNo);
        bucket.setBucketType(bucketType);
        bucket.setShardKey(shardKey);
        bucket.setSaleableQuantity(saleableQuantity);
        bucket.setOccupyQuantity(occupyQuantity);
        bucket.setSettingQuantity(settingQuantity);
        bucket.setStatus(status);
        bucket.setVersion(0L);
        bucket.setCreatedAt(now);
        bucket.setUpdatedAt(now);
        return bucket;
    }
}

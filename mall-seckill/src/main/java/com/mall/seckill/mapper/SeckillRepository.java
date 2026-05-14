package com.mall.seckill.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.common.exception.BusinessException;
import com.mall.seckill.pojo.entity.SeckillActivity;
import com.mall.seckill.pojo.entity.SeckillActivityEntity;
import com.mall.seckill.pojo.entity.SeckillResultEntity;
import com.mall.seckill.pojo.entity.SeckillSku;
import com.mall.seckill.pojo.entity.SeckillSkuEntity;
import com.mall.seckill.pojo.vo.SeckillActivityView;
import com.mall.seckill.pojo.vo.SeckillResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Repository
public class SeckillRepository {

    private static final ZoneId ZONE_ID = ZoneId.systemDefault();

    private final SeckillActivityMapper activityMapper;
    private final SeckillSkuMapper skuMapper;
    private final SeckillResultMapper resultMapper;

    public SeckillRepository(SeckillActivityMapper activityMapper,
                             SeckillSkuMapper skuMapper,
                             SeckillResultMapper resultMapper) {
        this.activityMapper = activityMapper;
        this.skuMapper = skuMapper;
        this.resultMapper = resultMapper;
    }

    public List<SeckillActivityView> activityViews() {
        return activityMapper.selectList(Wrappers.emptyWrapper()).stream()
                .map(activity -> new SeckillActivityView(
                        activity.getId(),
                        activity.getName(),
                        toInstant(activity.getStartAt()),
                        toInstant(activity.getEndAt()),
                        skuMapper.selectList(Wrappers.<SeckillSkuEntity>lambdaQuery()
                                        .eq(SeckillSkuEntity::getActivityId, activity.getId()))
                                .stream()
                                .map(this::toDomain)
                                .toList()
                ))
                .toList();
    }

    public SeckillActivity requireActivity(Long activityId) {
        SeckillActivityEntity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BusinessException(404, "Seckill activity not found");
        }
        return new SeckillActivity(activity.getId(), activity.getName(), toInstant(activity.getStartAt()), toInstant(activity.getEndAt()));
    }

    public SeckillSku requireSku(Long activityId, Long skuId) {
        SeckillSkuEntity sku = skuMapper.selectOne(Wrappers.<SeckillSkuEntity>lambdaQuery()
                .eq(SeckillSkuEntity::getActivityId, activityId)
                .eq(SeckillSkuEntity::getSkuId, skuId));
        if (sku == null) {
            throw new BusinessException(404, "Seckill SKU not found");
        }
        return toDomain(sku);
    }

    public int tryDeduct(Long activityId, Long skuId, Long userId) {
        return skuMapper.deductStock(activityId, skuId) == 0 ? 1 : 0;
    }

    public void saveResult(SeckillResult result) {
        SeckillResultEntity entity = new SeckillResultEntity();
        entity.setRequestId(result.requestId());
        entity.setStatus(result.status());
        entity.setOrderSn(result.orderSn());
        entity.setMessage(result.message());
        entity.setUpdatedAt(LocalDateTime.now());
        try {
            resultMapper.insert(entity);
        } catch (DuplicateKeyException exception) {
            resultMapper.updateById(entity);
        }
    }

    public SeckillResult result(String requestId) {
        SeckillResultEntity entity = resultMapper.selectById(requestId);
        if (entity == null) {
            return new SeckillResult(requestId, "PROCESSING", null, "Processing");
        }
        return new SeckillResult(entity.getRequestId(), entity.getStatus(), entity.getOrderSn(), entity.getMessage());
    }

    public Map<String, Integer> stockSnapshot() {
        return skuMapper.selectList(Wrappers.emptyWrapper()).stream()
                .collect(Collectors.toMap(
                        sku -> sku.getActivityId() + ":" + sku.getSkuId(),
                        SeckillSkuEntity::getStock
                ));
    }

    private SeckillSku toDomain(SeckillSkuEntity entity) {
        return new SeckillSku(
                entity.getActivityId(),
                entity.getSkuId(),
                entity.getSkuName(),
                entity.getSeckillPrice(),
                entity.getStock()
        );
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? Instant.now() : value.atZone(ZONE_ID).toInstant();
    }
}

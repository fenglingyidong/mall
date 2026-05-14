package com.mall.coupon.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.coupon.pojo.entity.ProductCouponEntity;
import com.mall.coupon.pojo.vo.CouponView;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public class CouponRepository {

    private final ProductCouponMapper productCouponMapper;

    public CouponRepository(ProductCouponMapper productCouponMapper) {
        this.productCouponMapper = productCouponMapper;
    }

    public List<CouponView> available(Long skuId) {
        return productCouponMapper.selectList(Wrappers.<ProductCouponEntity>lambdaQuery()
                        .eq(ProductCouponEntity::getSkuId, skuId)
                        .eq(ProductCouponEntity::getEnabled, true)
                        .gt(ProductCouponEntity::getStock, 0)
                        .gt(ProductCouponEntity::getExpireAt, LocalDateTime.now())
                        .orderByAsc(ProductCouponEntity::getThresholdAmount))
                .stream()
                .map(this::toView)
                .toList();
    }

    private CouponView toView(ProductCouponEntity entity) {
        return new CouponView(
                entity.getId(),
                entity.getTitle(),
                entity.getThresholdAmount(),
                entity.getDiscountAmount(),
                entity.getExpireAt()
        );
    }
}

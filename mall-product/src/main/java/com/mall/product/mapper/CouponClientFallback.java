package com.mall.product.mapper;

import com.mall.product.pojo.vo.CouponView;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CouponClientFallback implements CouponClient {

    @Override
    public List<CouponView> available(Long skuId, Long userId) {
        return List.of();
    }
}

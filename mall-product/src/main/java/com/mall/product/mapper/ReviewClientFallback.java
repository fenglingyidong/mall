package com.mall.product.mapper;

import com.mall.product.pojo.vo.ReviewSummary;
import org.springframework.stereotype.Component;

@Component
public class ReviewClientFallback implements ReviewClient {

    @Override
    public ReviewSummary summary(Long skuId) {
        return ReviewSummary.empty(skuId);
    }
}

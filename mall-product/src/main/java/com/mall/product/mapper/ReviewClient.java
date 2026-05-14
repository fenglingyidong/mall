package com.mall.product.mapper;

import com.mall.product.pojo.vo.ReviewSummary;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "mall-review", fallback = ReviewClientFallback.class)
public interface ReviewClient {

    @GetMapping("/internal/review/summary/{skuId}")
    ReviewSummary summary(@PathVariable("skuId") Long skuId);
}

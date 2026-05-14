package com.mall.review.service;

import com.mall.review.pojo.vo.ReviewSummary;

public interface ReviewService {

    ReviewSummary summary(Long skuId);
}

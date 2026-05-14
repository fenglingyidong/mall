package com.mall.review.service.impl;

import com.mall.review.mapper.ReviewRepository;
import com.mall.review.pojo.vo.ReviewSummary;
import com.mall.review.service.ReviewService;
import org.springframework.stereotype.Service;

@Service
public class ReviewServiceImpl implements ReviewService {

    private final ReviewRepository repository;

    public ReviewServiceImpl(ReviewRepository repository) {
        this.repository = repository;
    }

    @Override
    public ReviewSummary summary(Long skuId) {
        return repository.summary(skuId);
    }
}

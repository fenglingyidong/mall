package com.mall.review.controller;

import com.mall.review.pojo.vo.ReviewSummary;
import com.mall.review.service.ReviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReviewController {

    @Autowired
    private ReviewService reviewService;

    @GetMapping("/internal/review/summary/{skuId}")
    public ReviewSummary summary(@PathVariable Long skuId) {
        return reviewService.summary(skuId);
    }
}

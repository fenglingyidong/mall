package com.mall.review.mapper;

import com.mall.review.pojo.entity.ReviewSummaryEntity;
import com.mall.review.pojo.vo.ReviewSummary;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

@Repository
public class ReviewRepository {

    private final ReviewSummaryMapper reviewSummaryMapper;

    public ReviewRepository(ReviewSummaryMapper reviewSummaryMapper) {
        this.reviewSummaryMapper = reviewSummaryMapper;
    }

    public ReviewSummary summary(Long skuId) {
        ReviewSummaryEntity entity = reviewSummaryMapper.selectById(skuId);
        if (entity == null) {
            return new ReviewSummary(skuId, BigDecimal.ZERO, 0, BigDecimal.ZERO, null);
        }
        return new ReviewSummary(
                entity.getSkuId(),
                entity.getAverageRating(),
                entity.getReviewCount(),
                entity.getGoodRate(),
                entity.getLatestReview()
        );
    }
}

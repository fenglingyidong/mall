package com.mall.review.pojo.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("review_summary")
public class ReviewSummaryEntity {

    @TableId
    private Long skuId;

    private BigDecimal averageRating;

    private Integer reviewCount;

    private BigDecimal goodRate;

    private String latestReview;
}

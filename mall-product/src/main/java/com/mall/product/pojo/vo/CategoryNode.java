package com.mall.product.pojo.vo;

import java.util.List;

public record CategoryNode(Long categoryId, String categoryName, List<CategoryNode> children) {
}



package com.mall.product.service;

import com.mall.product.pojo.dto.StockDeductRequest;
import com.mall.product.pojo.vo.CategoryNode;
import com.mall.product.pojo.vo.ProductDetail;

import java.util.List;

public interface ProductService {

    ProductDetail detail(Long skuId);

    List<CategoryNode> categoryTree();

    void deductStock(StockDeductRequest request);

    void releaseStock(StockDeductRequest request);

    void invalidate(Long skuId);
}

package com.mall.product.service;

import com.mall.product.pojo.dto.StockDeductRequest;
import com.mall.product.pojo.vo.CategoryNode;
import com.mall.product.pojo.vo.ProductDetail;
import com.mall.product.pojo.vo.ProductSearchItem;

import java.math.BigDecimal;
import java.util.List;

public interface ProductService {

    ProductDetail detail(Long skuId);

    List<ProductSearchItem> search(String keyword, Long categoryId, String brand, BigDecimal minPrice, BigDecimal maxPrice, Integer limit);

    List<ProductSearchItem> searchByName(String name, Integer limit);

    List<CategoryNode> categoryTree();

    void deductStock(StockDeductRequest request);

    void releaseStock(StockDeductRequest request);

    void invalidate(Long skuId);
}

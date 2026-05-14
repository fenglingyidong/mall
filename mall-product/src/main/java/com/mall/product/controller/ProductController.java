package com.mall.product.controller;

import com.mall.common.api.ApiResponse;
import com.mall.product.pojo.dto.StockDeductRequest;
import com.mall.product.pojo.vo.CategoryNode;
import com.mall.product.pojo.vo.ProductDetail;
import com.mall.product.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping("/api/product/{skuId}")
    public ApiResponse<ProductDetail> detail(@PathVariable Long skuId) {
        return ApiResponse.success(productService.detail(skuId));
    }

    @GetMapping("/api/product/category/tree")
    public ApiResponse<List<CategoryNode>> categoryTree() {
        return ApiResponse.success(productService.categoryTree());
    }

    @GetMapping("/internal/product/sku/{skuId}")
    public ProductDetail internalDetail(@PathVariable Long skuId) {
        return productService.detail(skuId);
    }

    @PostMapping("/internal/product/stock/deduct")
    public ApiResponse<Void> deduct(@Valid @RequestBody StockDeductRequest request) {
        productService.deductStock(request);
        return ApiResponse.success();
    }

    @PostMapping("/internal/product/stock/release")
    public ApiResponse<Void> release(@Valid @RequestBody StockDeductRequest request) {
        productService.releaseStock(request);
        return ApiResponse.success();
    }

    @PostMapping("/internal/product/cache/invalidate/{skuId}")
    public ApiResponse<Void> invalidate(@PathVariable Long skuId) {
        productService.invalidate(skuId);
        return ApiResponse.success();
    }
}

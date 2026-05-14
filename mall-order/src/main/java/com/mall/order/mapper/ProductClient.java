package com.mall.order.mapper;

import com.mall.common.api.ApiResponse;
import com.mall.order.pojo.dto.StockDeductRequest;
import com.mall.order.pojo.vo.ProductSkuView;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@FeignClient(name = "mall-product", fallback = ProductClientFallback.class)
public interface ProductClient {

    @GetMapping("/internal/product/sku/{skuId}")
    ProductSkuView detail(@PathVariable("skuId") Long skuId);

    @PostMapping("/internal/product/stock/deduct")
    ApiResponse<Void> deduct(@RequestBody StockDeductRequest request);

    default ApiResponse<Void> deduct(List<StockDeductRequest.Item> items) {
        return deduct(new StockDeductRequest(items));
    }

    @PostMapping("/internal/product/stock/release")
    ApiResponse<Void> release(@RequestBody StockDeductRequest request);

    default ApiResponse<Void> release(List<StockDeductRequest.Item> items) {
        return release(new StockDeductRequest(items));
    }
}

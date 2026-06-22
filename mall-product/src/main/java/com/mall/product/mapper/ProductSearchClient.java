package com.mall.product.mapper;

import com.mall.product.pojo.vo.ProductSearchItem;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "mall-product", fallback = ProductSearchClientFallback.class)
public interface ProductSearchClient {

    @GetMapping("/internal/product/search/name")
    List<ProductSearchItem> searchByName(@RequestParam("name") String name,
                                         @RequestParam(value = "limit", required = false) Integer limit);
}

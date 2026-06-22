package com.mall.common.client;

import com.mall.common.client.vo.ProductSearchItem;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "mall-product", path = "/internal/product", contextId = "productSearchClient")
public interface ProductSearchClient {

    @GetMapping("/search/name")
    List<ProductSearchItem> searchByName(@RequestParam("name") String name,
                                         @RequestParam(value = "limit", required = false) Integer limit);
}

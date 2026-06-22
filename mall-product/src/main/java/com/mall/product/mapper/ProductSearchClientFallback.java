package com.mall.product.mapper;

import com.mall.product.pojo.vo.ProductSearchItem;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ProductSearchClientFallback implements ProductSearchClient {

    @Override
    public List<ProductSearchItem> searchByName(String name, Integer limit) {
        return List.of();
    }
}

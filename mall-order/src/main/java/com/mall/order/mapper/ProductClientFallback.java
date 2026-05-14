package com.mall.order.mapper;

import com.mall.common.api.ApiResponse;
import com.mall.order.pojo.dto.StockDeductRequest;
import com.mall.order.pojo.vo.ProductSkuView;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class ProductClientFallback implements ProductClient {

    @Override
    public ProductSkuView detail(Long skuId) {
        return new ProductSkuView(skuId, 100L, "SKU-" + skuId, "Demo Product", "Demo Category",
                "Demo Brand", new BigDecimal("99.00"), 999, "Fallback", List.of());
    }

    @Override
    public ApiResponse<Void> deduct(StockDeductRequest request) {
        return ApiResponse.fail(503, "Product stock service unavailable");
    }

    @Override
    public ApiResponse<Void> release(StockDeductRequest request) {
        return ApiResponse.fail(503, "Product stock service unavailable");
    }
}

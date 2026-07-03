package com.mall.seckill.controller;

import com.mall.common.api.ApiResponse;
import com.mall.common.exception.BusinessException;
import com.mall.seckill.config.SeckillProperties;
import com.mall.seckill.mapper.SeckillRepository;
import com.mall.seckill.pojo.entity.SeckillSku;
import com.mall.seckill.pojo.vo.StockDeductProbeResponse;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InternalSeckillLoadTestController {

    private static final int SECKILL_QUANTITY = 1;

    private final SeckillRepository repository;
    private final SeckillProperties properties;
    private final ConcurrentMap<String, Long> stockIdCache = new ConcurrentHashMap<>();

    public InternalSeckillLoadTestController(SeckillRepository repository, SeckillProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @PostMapping("/internal/seckill/loadtest/stock-deduct/{activityId}/{skuId}")
    public ApiResponse<StockDeductProbeResponse> deductStock(@PathVariable Long activityId,
                                                             @PathVariable Long skuId) {
        if (!properties.getLoadTest().isStockDeductEnabled()) {
            throw new BusinessException(404, "Stock deduct load test endpoint disabled");
        }
        Long stockId = stockIdCache.computeIfAbsent(stockKey(activityId, skuId), key -> resolveStockId(activityId, skuId));
        return ApiResponse.success(repository.deductStockOnly(stockId, SECKILL_QUANTITY));
    }

    @PostMapping("/internal/seckill/loadtest/stock-deduct-tx-update-select/{activityId}/{skuId}")
    public ApiResponse<StockDeductProbeResponse> deductStockWithTransactionUpdateSelect(@PathVariable Long activityId,
                                                                                        @PathVariable Long skuId) {
        return deductStock(activityId, skuId);
    }

    @PostMapping("/internal/seckill/loadtest/stock-deduct-update-only/{activityId}/{skuId}")
    public ApiResponse<StockDeductProbeResponse> deductStockUpdateOnly(@PathVariable Long activityId,
                                                                       @PathVariable Long skuId) {
        if (!properties.getLoadTest().isStockDeductEnabled()) {
            throw new BusinessException(404, "Stock deduct load test endpoint disabled");
        }
        Long stockId = stockIdCache.computeIfAbsent(stockKey(activityId, skuId), key -> resolveStockId(activityId, skuId));
        return ApiResponse.success(repository.deductStockUpdateOnly(stockId, SECKILL_QUANTITY));
    }

    private Long resolveStockId(Long activityId, Long skuId) {
        SeckillSku sku = repository.requireSku(activityId, skuId);
        return sku.id();
    }

    private String stockKey(Long activityId, Long skuId) {
        return activityId + ":" + skuId;
    }
}

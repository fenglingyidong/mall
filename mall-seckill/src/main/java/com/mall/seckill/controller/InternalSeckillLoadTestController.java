package com.mall.seckill.controller;

import com.mall.common.api.ApiResponse;
import com.mall.common.exception.BusinessException;
import com.mall.seckill.config.SeckillProperties;
import com.mall.seckill.mapper.SeckillRepository;
import com.mall.seckill.pojo.entity.SeckillSku;
import com.mall.seckill.pojo.vo.StockDeductProbeResponse;
import com.mall.seckill.service.impl.SeckillBucketService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@RestController
public class InternalSeckillLoadTestController {

    private static final int SECKILL_QUANTITY = 1;

    private final SeckillRepository repository;
    private final SeckillBucketService bucketService;
    private final SeckillProperties properties;
    private final ConcurrentMap<String, Long> stockIdCache = new ConcurrentHashMap<>();

    public InternalSeckillLoadTestController(SeckillRepository repository,
                                             SeckillBucketService bucketService,
                                             SeckillProperties properties) {
        this.repository = repository;
        this.bucketService = bucketService;
        this.properties = properties;
    }

    @PostMapping("/internal/seckill/loadtest/stock-deduct/{activityId}/{skuId}")
    public ApiResponse<StockDeductProbeResponse> deductStock(@PathVariable Long activityId,
                                                             @PathVariable Long skuId) {
        requireStockDeductLoadTestEnabled();
        Long stockId = stockIdCache.computeIfAbsent(stockKey(activityId, skuId), key -> resolveStockId(activityId, skuId));
        return ApiResponse.success(repository.deductStockOnly(stockId, SECKILL_QUANTITY));
    }

    @PostMapping("/internal/seckill/loadtest/stock-deduct-tx-update-select/{activityId}/{skuId}")
    public ApiResponse<StockDeductProbeResponse> deductStockWithTransactionUpdateSelect(@PathVariable Long activityId,
                                                                                       @PathVariable Long skuId) {
        requireStockDeductLoadTestEnabled();
        Long stockId = stockIdCache.computeIfAbsent(stockKey(activityId, skuId), key -> resolveStockId(activityId, skuId));
        return ApiResponse.success(repository.deductStockOnly(stockId, SECKILL_QUANTITY));
    }

    @PostMapping("/internal/seckill/loadtest/stock-deduct-update-only/{activityId}/{skuId}")
    public ApiResponse<StockDeductProbeResponse> deductStockUpdateOnly(@PathVariable Long activityId,
                                                                       @PathVariable Long skuId) {
        requireStockDeductLoadTestEnabled();
        Long stockId = stockIdCache.computeIfAbsent(stockKey(activityId, skuId), key -> resolveStockId(activityId, skuId));
        return ApiResponse.success(repository.deductStockUpdateOnly(stockId, SECKILL_QUANTITY));
    }

    @PostMapping("/internal/seckill/loadtest/bucket-deduct-only/{activityId}/{skuId}")
    public ApiResponse<StockDeductProbeResponse> deductBucketOnly(@PathVariable Long activityId,
                                                                  @PathVariable Long skuId) {
        requireStockDeductLoadTestEnabled();
        if (!properties.getBucket().isEnabled()) {
            throw new BusinessException(404, "Bucket deduct load test endpoint disabled");
        }
        SeckillBucketService.BucketDeductOnlyResult result = bucketService.deductOnly(activityId, skuId, SECKILL_QUANTITY);
        return ApiResponse.success(new StockDeductProbeResponse(result.deducted(), null, null));
    }

    private void requireStockDeductLoadTestEnabled() {
        if (!properties.getLoadTest().isStockDeductEnabled()) {
            throw new BusinessException(404, "Stock deduct load test endpoint disabled");
        }
    }

    private Long resolveStockId(Long activityId, Long skuId) {
        SeckillSku sku = repository.requireSku(activityId, skuId);
        return sku.id();
    }

    private String stockKey(Long activityId, Long skuId) {
        return activityId + ":" + skuId;
    }
}

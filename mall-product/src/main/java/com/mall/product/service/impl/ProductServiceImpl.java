package com.mall.product.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.context.UserContext;
import com.mall.common.exception.BusinessException;
import com.mall.common.util.BloomFilter;
import com.mall.common.util.JsonUtils;
import com.mall.product.mapper.CouponClient;
import com.mall.product.mapper.ProductRepository;
import com.mall.product.mapper.ReviewClient;
import com.mall.product.pojo.dto.StockDeductRequest;
import com.mall.product.pojo.entity.ProductDetailRow;
import com.mall.product.pojo.vo.CategoryNode;
import com.mall.product.pojo.vo.CouponView;
import com.mall.product.pojo.vo.ProductCoreDetail;
import com.mall.product.pojo.vo.ProductDetail;
import com.mall.product.pojo.vo.ProductSearchItem;
import com.mall.product.pojo.vo.ReviewSummary;
import com.mall.product.service.ProductService;
import com.mall.product.service.StockTccAction;
import io.seata.core.context.RootContext;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@Service
public class ProductServiceImpl implements ProductService {

    private final ProductRepository repository;
    private final ProductCache cache;
    private final ReviewClient reviewClient;
    private final CouponClient couponClient;
    private final Executor productDetailExecutor;
    private final StockTccAction stockTccAction;
    private final ObjectMapper objectMapper;
    private final BloomFilter bloomFilter = new BloomFilter(1 << 20);
    private final Map<Long, Object> locks = new ConcurrentHashMap<>();

    public ProductServiceImpl(ProductRepository repository,
                              ProductCache cache,
                              ReviewClient reviewClient,
                              CouponClient couponClient,
                              @Qualifier("productDetailExecutor") Executor productDetailExecutor,
                              StockTccAction stockTccAction,
                              ObjectMapper objectMapper) {
        this.repository = repository;
        this.cache = cache;
        this.reviewClient = reviewClient;
        this.couponClient = couponClient;
        this.productDetailExecutor = productDetailExecutor;
        this.stockTccAction = stockTccAction;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initBloomFilter() {
        bloomFilter.addAll(repository.allSkuIds());
    }

    @Override
    public ProductDetail detail(Long skuId) {
        if (!bloomFilter.mightContain(String.valueOf(skuId))) {
            throw new BusinessException(404, "Product not found");
        }
        CompletableFuture<ReviewSummary> reviewFuture = reviewSummaryFuture(skuId);
        CompletableFuture<List<CouponView>> couponFuture = couponFuture(skuId, UserContext.currentUserIdOrDefault(1L));
        ProductCoreDetail coreDetail = coreDetail(skuId);
        return toDetail(coreDetail, reviewFuture.join(), couponFuture.join());
    }

    @Override
    public List<ProductSearchItem> search(String keyword,
                                          Long categoryId,
                                          String brand,
                                          BigDecimal minPrice,
                                          BigDecimal maxPrice,
                                          Integer limit) {
        int safeLimit = limit == null ? 10 : Math.max(1, Math.min(limit, 50));
        return repository.search(keyword, categoryId, brand, minPrice, maxPrice, safeLimit);
    }

    @Override
    public List<ProductSearchItem> searchByName(String name, Integer limit) {
        int safeLimit = limit == null ? 10 : Math.max(1, Math.min(limit, 50));
        return repository.searchByName(name, safeLimit);
    }

    private ProductCoreDetail coreDetail(Long skuId) {
        ProductCoreDetail cached = cache.get(skuId);
        if (cached != null) {
            return cached;
        }
        Object lock = locks.computeIfAbsent(skuId, ignored -> new Object());
        synchronized (lock) {
            ProductCoreDetail secondCheck = cache.get(skuId);
            if (secondCheck != null) {
                return secondCheck;
            }
            ProductCoreDetail coreDetail = buildCoreDetail(skuId);
            cache.put(skuId, coreDetail);
            return coreDetail;
        }
    }

    @Override
    public List<CategoryNode> categoryTree() {
        return List.of(
                new CategoryNode(10L, "Digital", List.of(
                        new CategoryNode(100L, "Audio", List.of())
                )),
                new CategoryNode(11L, "Sports", List.of(
                        new CategoryNode(101L, "Running", List.of())
                ))
        );
    }

    @Override
    public void deductStock(StockDeductRequest request) {
        if (RootContext.inGlobalTransaction()) {
            String itemsJson = JsonUtils.toJson(objectMapper, request.items());
            if (!stockTccAction.prepare(null, itemsJson)) {
                throw new BusinessException(409, "Stock TCC prepare failed");
            }
            return;
        }
        repository.deduct(request.items());
        request.items().forEach(item -> cache.invalidate(item.skuId()));
    }

    @Override
    public void releaseStock(StockDeductRequest request) {
        repository.release(request.items());
        request.items().forEach(item -> cache.invalidate(item.skuId()));
    }

    @Override
    public void invalidate(Long skuId) {
        bloomFilter.add(String.valueOf(skuId));
        cache.invalidate(skuId);
    }

    private ProductCoreDetail buildCoreDetail(Long skuId) {
        ProductDetailRow detail = repository.detailRow(skuId);
        return new ProductCoreDetail(
                detail.getSkuId(),
                detail.getSpuId(),
                detail.getSkuName(),
                detail.getSpuName(),
                detail.getCategoryName(),
                detail.getBrandName(),
                detail.getPrice(),
                detail.getStock(),
                "Save 20 over 199",
                repository.optionsBySpu(detail.getSpuId())
        );
    }

    private ProductDetail toDetail(ProductCoreDetail core,
                                   ReviewSummary reviewSummary,
                                   List<CouponView> coupons) {
        return new ProductDetail(
                core.skuId(),
                core.spuId(),
                core.skuName(),
                core.spuName(),
                core.categoryName(),
                core.brandName(),
                core.price(),
                core.stock(),
                core.promotion(),
                core.skuOptions(),
                reviewSummary,
                coupons
        );
    }

    private CompletableFuture<ReviewSummary> reviewSummaryFuture(Long skuId) {
        return CompletableFuture.supplyAsync(() -> reviewClient.summary(skuId), productDetailExecutor)
                .completeOnTimeout(ReviewSummary.empty(skuId), 250, TimeUnit.MILLISECONDS)
                .exceptionally(throwable -> ReviewSummary.empty(skuId));
    }

    private CompletableFuture<List<CouponView>> couponFuture(Long skuId, Long userId) {
        return CompletableFuture.supplyAsync(() -> couponClient.available(skuId, userId), productDetailExecutor)
                .completeOnTimeout(List.of(), 250, TimeUnit.MILLISECONDS)
                .exceptionally(throwable -> List.of());
    }
}

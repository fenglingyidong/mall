package com.mall.product.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.common.exception.BusinessException;
import com.mall.product.pojo.dto.StockDeductRequest;
import com.mall.product.pojo.entity.ProductDetailRow;
import com.mall.product.pojo.entity.SkuEntity;
import com.mall.product.pojo.entity.SpuEntity;
import com.mall.product.pojo.vo.SkuOption;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public class ProductRepository {

    private final SkuMapper skuMapper;
    private final SpuMapper spuMapper;
    private final SkuStockMapper skuStockMapper;

    public ProductRepository(SkuMapper skuMapper,
                             SpuMapper spuMapper,
                             SkuStockMapper skuStockMapper) {
        this.skuMapper = skuMapper;
        this.spuMapper = spuMapper;
        this.skuStockMapper = skuStockMapper;
    }

    public ProductDetailRow detailRow(Long skuId) {
        ProductDetailRow detail = skuMapper.selectDetail(skuId);
        if (detail == null) {
            throw new BusinessException(404, "Product not found");
        }
        return detail;
    }

    public List<SkuOption> optionsBySpu(Long spuId) {
        return skuMapper.selectList(Wrappers.<SkuEntity>lambdaQuery().eq(SkuEntity::getSpuId, spuId))
                .stream()
                .map(sku -> new SkuOption(sku.getId(), sku.getName(), sku.getPrice()))
                .toList();
    }

    public List<String> allSkuIds() {
        return skuMapper.selectList(Wrappers.<SkuEntity>lambdaQuery().select(SkuEntity::getId))
                .stream()
                .map(sku -> String.valueOf(sku.getId()))
                .toList();
    }

    public List<Long> skuIdsBySpu(Long spuId) {
        if (spuId == null) {
            return List.of();
        }
        return skuMapper.selectList(Wrappers.<SkuEntity>lambdaQuery()
                        .select(SkuEntity::getId)
                        .eq(SkuEntity::getSpuId, spuId))
                .stream()
                .map(SkuEntity::getId)
                .toList();
    }

    public List<Long> skuIdsByBrand(Long brandId) {
        if (brandId == null) {
            return List.of();
        }
        return spuMapper.selectList(Wrappers.<SpuEntity>lambdaQuery()
                        .select(SpuEntity::getId)
                        .eq(SpuEntity::getBrandId, brandId))
                .stream()
                .flatMap(spu -> skuIdsBySpu(spu.getId()).stream())
                .toList();
    }

    public List<Long> skuIdsByCategory(Long categoryId) {
        if (categoryId == null) {
            return List.of();
        }
        return spuMapper.selectList(Wrappers.<SpuEntity>lambdaQuery()
                        .select(SpuEntity::getId)
                        .eq(SpuEntity::getCategoryId, categoryId))
                .stream()
                .flatMap(spu -> skuIdsBySpu(spu.getId()).stream())
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public void deduct(List<StockDeductRequest.Item> items) {
        for (StockDeductRequest.Item item : items) {
            int updated = skuStockMapper.deductStock(item.skuId(), item.quantity());
            if (updated == 0) {
                throw new BusinessException(409, "Stock not enough: " + item.skuId());
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void reserve(List<StockDeductRequest.Item> items) {
        for (StockDeductRequest.Item item : items) {
            int updated = skuStockMapper.reserveStock(item.skuId(), item.quantity());
            if (updated == 0) {
                throw new BusinessException(409, "Stock not enough: " + item.skuId());
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void confirmReserved(List<StockDeductRequest.Item> items) {
        for (StockDeductRequest.Item item : items) {
            skuStockMapper.confirmReservedStock(item.skuId(), item.quantity());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void cancelReserved(List<StockDeductRequest.Item> items) {
        for (StockDeductRequest.Item item : items) {
            skuStockMapper.cancelReservedStock(item.skuId(), item.quantity());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void release(List<StockDeductRequest.Item> items) {
        for (StockDeductRequest.Item item : items) {
            skuStockMapper.releaseStock(item.skuId(), item.quantity());
        }
    }
}

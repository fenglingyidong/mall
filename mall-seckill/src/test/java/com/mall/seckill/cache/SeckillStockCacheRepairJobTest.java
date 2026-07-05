package com.mall.seckill.cache;

import com.mall.seckill.config.SeckillProperties;
import com.mall.seckill.mapper.SeckillSkuMapper;
import com.mall.seckill.pojo.entity.SeckillSkuEntity;
import com.mall.seckill.pojo.vo.StockVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillStockCacheRepairJobTest {

    @Mock
    private SeckillSkuMapper skuMapper;

    @Mock
    private SeckillStockCache stockCache;

    private SeckillProperties properties;
    private SeckillStockCacheRepairJob repairJob;

    @BeforeEach
    void setUp() {
        properties = new SeckillProperties();
        properties.getStockCache().getRepair().setLimit(100);
        repairJob = new SeckillStockCacheRepairJob(skuMapper, stockCache, properties);
    }

    @Test
    void shouldRefreshVersionedCacheForSkuStocks() {
        SeckillSkuEntity first = sku(1L, 1L, 1001L, 9, 3L);
        SeckillSkuEntity second = sku(2L, 1L, 1002L, 0, 12L);
        when(skuMapper.selectList(any())).thenReturn(List.of(first, second));

        repairJob.repair();

        verify(stockCache).refresh(1L, 1001L, new StockVersion(9, 3L));
        verify(stockCache).refresh(1L, 1002L, new StockVersion(0, 12L));
    }

    @Test
    void shouldContinueWhenOneRefreshFails() {
        SeckillSkuEntity first = sku(1L, 1L, 1001L, 9, 3L);
        SeckillSkuEntity second = sku(2L, 1L, 1002L, 0, 12L);
        when(skuMapper.selectList(any())).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("cache failed"))
                .when(stockCache).refresh(1L, 1001L, new StockVersion(9, 3L));

        repairJob.repair();

        verify(stockCache).refresh(1L, 1001L, new StockVersion(9, 3L));
        verify(stockCache).refresh(1L, 1002L, new StockVersion(0, 12L));
    }

    private SeckillSkuEntity sku(Long id, Long activityId, Long skuId, Integer stock, Long version) {
        SeckillSkuEntity sku = new SeckillSkuEntity();
        sku.setId(id);
        sku.setActivityId(activityId);
        sku.setSkuId(skuId);
        sku.setStock(stock);
        sku.setVersion(version);
        return sku;
    }
}

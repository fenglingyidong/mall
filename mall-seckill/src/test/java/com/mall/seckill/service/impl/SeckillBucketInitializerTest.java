package com.mall.seckill.service.impl;

import com.mall.seckill.mapper.SeckillBucketConfigMapper;
import com.mall.seckill.mapper.SeckillSkuMapper;
import com.mall.seckill.mapper.SeckillStockBucketMapper;
import com.mall.seckill.pojo.entity.SeckillBucketConfigEntity;
import com.mall.seckill.pojo.entity.SeckillSkuEntity;
import com.mall.seckill.pojo.entity.SeckillStockBucketEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillBucketInitializerTest {

    @Mock
    private SeckillSkuMapper skuMapper;

    @Mock
    private SeckillBucketConfigMapper configMapper;

    @Mock
    private SeckillStockBucketMapper bucketMapper;

    private SeckillBucketInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new SeckillBucketInitializer(skuMapper, configMapper, bucketMapper);
    }

    @Test
    void shouldInitializeCenterAndBusinessBucketsFromSkuStock() {
        SeckillSkuEntity sku = new SeckillSkuEntity();
        sku.setActivityId(1L);
        sku.setSkuId(1001L);
        sku.setStock(5);
        when(configMapper.selectEnabled(1L, 1001L)).thenReturn(null);
        when(skuMapper.selectOne(any())).thenReturn(sku);

        initializer.initializeFromSku(1L, 1001L, 3);

        ArgumentCaptor<SeckillBucketConfigEntity> configCaptor = ArgumentCaptor.forClass(SeckillBucketConfigEntity.class);
        verify(configMapper).insert(configCaptor.capture());
        assertThat(configCaptor.getValue().getBucketCount()).isEqualTo(3);
        assertThat(configCaptor.getValue().getRouteMode()).isEqualTo("RANDOM_ACTIVE");
        assertThat(configCaptor.getValue().getSurvivorBuckets()).isEqualTo("1,2,3");

        ArgumentCaptor<SeckillStockBucketEntity> bucketCaptor = ArgumentCaptor.forClass(SeckillStockBucketEntity.class);
        verify(bucketMapper, org.mockito.Mockito.times(4)).insert(bucketCaptor.capture());
        List<SeckillStockBucketEntity> buckets = bucketCaptor.getAllValues();
        assertThat(buckets).extracting(SeckillStockBucketEntity::getBucketNo)
                .containsExactly(0, 1, 2, 3);
        assertThat(buckets).extracting(SeckillStockBucketEntity::getSaleableQuantity)
                .containsExactly(5, 2, 2, 1);
        assertThat(buckets.get(0).getBucketType()).isEqualTo("CENTER");
        assertThat(buckets.subList(1, 4)).allSatisfy(bucket -> assertThat(bucket.getBucketType()).isEqualTo("BUCKET"));
    }

    @Test
    void shouldSkipWhenBucketConfigAlreadyExists() {
        when(configMapper.selectEnabled(1L, 1001L)).thenReturn(new SeckillBucketConfigEntity());

        initializer.initializeFromSku(1L, 1001L, 3);

        verify(skuMapper, never()).selectOne(any());
        verify(bucketMapper, never()).insert(any(SeckillStockBucketEntity.class));
    }
}

package com.mall.seckill.service.impl;

import com.mall.seckill.config.SeckillProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeckillBucketShardRouterTest {

    @Test
    void shouldUseBucketNoAsStage3CShardKey() {
        SeckillBucketShardRouter router = new SeckillBucketShardRouter(new SeckillProperties());

        assertThat(router.bucketShardKey(7)).isEqualTo(7L);
        assertThat(router.centerShardKey()).isZero();
    }

    @Test
    void shouldMapShardKeyToPhysicalShardTag() {
        SeckillProperties properties = new SeckillProperties();
        properties.getBucket().getRouting().setPhysicalShardCount(2);
        SeckillBucketShardRouter router = new SeckillBucketShardRouter(properties);

        assertThat(router.physicalShardTag(1L)).isEqualTo("1");
        assertThat(router.physicalShardTag(2L)).isEqualTo("0");
    }

    @Test
    void shouldRejectInvalidBucketNo() {
        SeckillBucketShardRouter router = new SeckillBucketShardRouter(new SeckillProperties());

        assertThatThrownBy(() -> router.bucketShardKey(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

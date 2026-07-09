package com.mall.seckill.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class SeckillStockBucketMapperSqlTest {

    @Test
    void selectActiveBucketByShardMustOnlyReturnPositiveSaleableBuckets() throws Exception {
        Method method = SeckillStockBucketMapper.class.getMethod(
                "selectActiveBucketByShard",
                Long.class,
                Long.class,
                Integer.class,
                Long.class);

        Select select = method.getAnnotation(Select.class);

        assertThat(select).isNotNull();
        assertThat(String.join(" ", select.value()))
                .contains("status = 'ACTIVE'")
                .contains("saleable_quantity > 0");
    }
}

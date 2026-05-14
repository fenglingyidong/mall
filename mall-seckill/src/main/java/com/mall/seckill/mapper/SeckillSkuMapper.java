package com.mall.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.seckill.pojo.entity.SeckillSkuEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SeckillSkuMapper extends BaseMapper<SeckillSkuEntity> {

    @Update("UPDATE seckill_sku SET stock = stock - 1 WHERE activity_id = #{activityId} AND sku_id = #{skuId} AND stock > 0")
    int deductStock(@Param("activityId") Long activityId, @Param("skuId") Long skuId);
}

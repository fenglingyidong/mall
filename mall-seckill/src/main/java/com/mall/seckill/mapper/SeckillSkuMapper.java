package com.mall.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.seckill.pojo.entity.SeckillSkuEntity;
import com.mall.seckill.pojo.vo.StockVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SeckillSkuMapper extends BaseMapper<SeckillSkuEntity> {

    @Update("UPDATE seckill_sku SET stock = stock - #{quantity}, version = version + 1 WHERE activity_id = #{activityId} AND sku_id = #{skuId} AND stock >= #{quantity}")
    int deductStockAndIncreaseVersion(@Param("activityId") Long activityId,
                                       @Param("skuId") Long skuId,
                                       @Param("quantity") Integer quantity);

    @Update("UPDATE seckill_sku SET stock = stock - #{quantity}, version = version + 1 WHERE id = #{id} AND stock >= #{quantity}")
    int deductStockAndIncreaseVersionById(@Param("id") Long id,
                                           @Param("quantity") Integer quantity);

    @Update("UPDATE seckill_sku SET stock = stock + #{quantity}, version = version + 1 WHERE activity_id = #{activityId} AND sku_id = #{skuId}")
    int releaseStockAndIncreaseVersion(@Param("activityId") Long activityId,
                                        @Param("skuId") Long skuId,
                                        @Param("quantity") Integer quantity);

    @Update("UPDATE seckill_sku SET stock = stock + #{quantity}, version = version + 1 WHERE id = #{id}")
    int releaseStockAndIncreaseVersionById(@Param("id") Long id,
                                            @Param("quantity") Integer quantity);

    @Select("SELECT stock, version FROM seckill_sku WHERE activity_id = #{activityId} AND sku_id = #{skuId}")
    StockVersion selectStockVersion(@Param("activityId") Long activityId,
                                    @Param("skuId") Long skuId);

    @Select("SELECT stock, version FROM seckill_sku WHERE id = #{id}")
    StockVersion selectStockVersionById(@Param("id") Long id);

    @Update("UPDATE seckill_sku SET stock = stock - #{quantity} WHERE activity_id = #{activityId} AND sku_id = #{skuId} AND stock >= #{quantity}")
    int deductStock(@Param("activityId") Long activityId, @Param("skuId") Long skuId, @Param("quantity") Integer quantity);

    @Update("UPDATE seckill_sku SET stock = stock + #{quantity} WHERE activity_id = #{activityId} AND sku_id = #{skuId}")
    int releaseStock(@Param("activityId") Long activityId, @Param("skuId") Long skuId, @Param("quantity") Integer quantity);

    @Select({
            "<script>",
            "SELECT * FROM seckill_sku",
            "WHERE activity_id = #{activityId}",
            "AND sku_id IN",
            "<foreach collection='skuIds' item='skuId' open='(' separator=',' close=')'>",
            "#{skuId}",
            "</foreach>",
            "</script>"
    })
    List<SeckillSkuEntity> selectByActivityIdAndSkuIds(@Param("activityId") Long activityId,
                                                       @Param("skuIds") List<Long> skuIds);
}

package com.mall.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.seckill.pojo.entity.SeckillBucketConfigEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SeckillBucketConfigMapper extends BaseMapper<SeckillBucketConfigEntity> {

    @Select("SELECT * FROM seckill_bucket_config WHERE activity_id = #{activityId} AND sku_id = #{skuId} AND status = 'ENABLED'")
    SeckillBucketConfigEntity selectEnabled(@Param("activityId") Long activityId,
                                            @Param("skuId") Long skuId);

    @Select("SELECT * FROM seckill_bucket_config WHERE status = 'ENABLED' ORDER BY id LIMIT #{limit}")
    List<SeckillBucketConfigEntity> selectEnabledForMaintenance(@Param("limit") Integer limit);

    @Update("UPDATE seckill_bucket_config SET survivor_buckets = #{survivorBuckets}, updated_at = NOW() WHERE id = #{id}")
    int updateSurvivorBuckets(@Param("id") Long id,
                              @Param("survivorBuckets") String survivorBuckets);

    @Update("UPDATE seckill_bucket_config SET survivor_buckets = CASE WHEN survivor_buckets IS NULL OR survivor_buckets = '' THEN #{bucketNo} WHEN FIND_IN_SET(#{bucketNo}, survivor_buckets) = 0 THEN CONCAT(survivor_buckets, ',', #{bucketNo}) ELSE survivor_buckets END, updated_at = NOW() WHERE activity_id = #{activityId} AND sku_id = #{skuId} AND status = 'ENABLED'")
    int addSurvivorBucket(@Param("activityId") Long activityId,
                          @Param("skuId") Long skuId,
                          @Param("bucketNo") Integer bucketNo);

    @Update("UPDATE seckill_bucket_config SET survivor_buckets = TRIM(BOTH ',' FROM REPLACE(CONCAT(',', COALESCE(survivor_buckets, ''), ','), CONCAT(',', #{bucketNo}, ','), ',')), updated_at = NOW() WHERE activity_id = #{activityId} AND sku_id = #{skuId} AND status = 'ENABLED'")
    int removeSurvivorBucket(@Param("activityId") Long activityId,
                             @Param("skuId") Long skuId,
                             @Param("bucketNo") Integer bucketNo);
}

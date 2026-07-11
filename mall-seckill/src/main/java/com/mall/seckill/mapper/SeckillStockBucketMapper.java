package com.mall.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.seckill.pojo.entity.SeckillStockBucketEntity;
import com.mall.seckill.pojo.vo.StockVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SeckillStockBucketMapper extends BaseMapper<SeckillStockBucketEntity> {

    @Select("SELECT * FROM seckill_stock_bucket WHERE activity_id = #{activityId} AND sku_id = #{skuId} AND bucket_no = #{bucketNo} AND bucket_type = 'BUCKET' AND status = 'ACTIVE'")
    SeckillStockBucketEntity selectActiveBucket(@Param("activityId") Long activityId,
                                                @Param("skuId") Long skuId,
                                                @Param("bucketNo") Integer bucketNo);

    @Select("SELECT * FROM seckill_stock_bucket WHERE activity_id = #{activityId} AND sku_id = #{skuId} AND bucket_no = #{bucketNo} AND shard_key = #{shardKey} AND bucket_type = 'BUCKET' AND status = 'ACTIVE' AND saleable_quantity > 0")
    SeckillStockBucketEntity selectActiveBucketByShard(@Param("activityId") Long activityId,
                                                       @Param("skuId") Long skuId,
                                                       @Param("bucketNo") Integer bucketNo,
                                                       @Param("shardKey") Long shardKey);

    @Select("SELECT * FROM seckill_stock_bucket WHERE id = #{id} AND shard_key = #{shardKey}")
    SeckillStockBucketEntity selectByIdAndShardKey(@Param("id") Long id,
                                                   @Param("shardKey") Long shardKey);

    @Update("UPDATE seckill_stock_bucket SET saleable_quantity = saleable_quantity - #{quantity}, version = version + 1, updated_at = NOW() WHERE id = #{id} AND bucket_type = 'BUCKET' AND status = 'ACTIVE' AND saleable_quantity >= #{quantity}")
    int deductSaleableAndIncreaseVersion(@Param("id") Long id,
                                          @Param("quantity") Integer quantity);

    @Update("UPDATE seckill_stock_bucket SET saleable_quantity = saleable_quantity - #{quantity}, version = version + 1, updated_at = NOW() WHERE id = #{id} AND shard_key = #{shardKey} AND bucket_type = 'BUCKET' AND status = 'ACTIVE' AND saleable_quantity >= #{quantity}")
    int deductSaleableAndIncreaseVersionByShard(@Param("id") Long id,
                                                @Param("shardKey") Long shardKey,
                                                @Param("quantity") Integer quantity);

    @Update("UPDATE seckill_stock_bucket SET saleable_quantity = saleable_quantity + #{quantity}, version = version + 1, status = 'ACTIVE', updated_at = NOW() WHERE id = #{id} AND bucket_type = 'BUCKET'")
    int releaseSaleableAndIncreaseVersion(@Param("id") Long id,
                                           @Param("quantity") Integer quantity);

    @Update("UPDATE seckill_stock_bucket SET saleable_quantity = saleable_quantity + #{quantity}, version = version + 1, status = 'ACTIVE', updated_at = NOW() WHERE id = #{id} AND shard_key = #{shardKey} AND bucket_type = 'BUCKET'")
    int releaseSaleableAndIncreaseVersionByShard(@Param("id") Long id,
                                                  @Param("shardKey") Long shardKey,
                                                  @Param("quantity") Integer quantity);

    @Select("SELECT * FROM seckill_stock_bucket WHERE activity_id = #{activityId} AND sku_id = #{skuId} AND bucket_type = 'BUCKET' AND status = 'ACTIVE' AND id <> #{excludedBucketId} AND saleable_quantity > #{reserveQuantity} ORDER BY saleable_quantity DESC, id ASC LIMIT 1")
    SeckillStockBucketEntity selectTransferSource(@Param("activityId") Long activityId,
                                                  @Param("skuId") Long skuId,
                                                  @Param("excludedBucketId") Long excludedBucketId,
                                                  @Param("reserveQuantity") Integer reserveQuantity);

    @Update("UPDATE seckill_stock_bucket SET saleable_quantity = saleable_quantity - #{quantity}, version = version + 1, updated_at = NOW() WHERE id = #{id} AND shard_key = #{shardKey} AND bucket_type = 'BUCKET' AND status = 'ACTIVE' AND saleable_quantity >= #{quantity} + #{reserveQuantity}")
    int deductTransferSource(@Param("id") Long id,
                             @Param("shardKey") Long shardKey,
                             @Param("quantity") Integer quantity,
                             @Param("reserveQuantity") Integer reserveQuantity);

    @Update("UPDATE seckill_stock_bucket SET saleable_quantity = saleable_quantity + #{quantity}, version = version + 1, status = 'ACTIVE', updated_at = NOW() WHERE id = #{id} AND shard_key = #{shardKey} AND bucket_type = 'BUCKET'")
    int addTransferTarget(@Param("id") Long id,
                          @Param("shardKey") Long shardKey,
                          @Param("quantity") Integer quantity);

    @Update("UPDATE seckill_stock_bucket SET status = #{status}, updated_at = NOW() WHERE id = #{id} AND shard_key = #{shardKey}")
    int updateStatus(@Param("id") Long id,
                     @Param("shardKey") Long shardKey,
                     @Param("status") String status);

    @Update("UPDATE seckill_stock_bucket SET status = 'EMPTY', updated_at = NOW() WHERE id = #{id} AND shard_key = #{shardKey} AND bucket_type = 'BUCKET' AND saleable_quantity <= 0")
    int markEmptyIfNoSaleable(@Param("id") Long id,
                              @Param("shardKey") Long shardKey);

    @Update("UPDATE seckill_stock_bucket SET status = 'EMPTY', updated_at = NOW() WHERE id = #{id} AND shard_key = #{shardKey} AND bucket_type = 'BUCKET' AND saleable_quantity <= 0")
    int markEmptyIfNoSaleableByShard(@Param("id") Long id,
                                     @Param("shardKey") Long shardKey);

    @Update("UPDATE seckill_stock_bucket SET status = 'ACTIVE', updated_at = NOW() WHERE activity_id = #{activityId} AND sku_id = #{skuId} AND bucket_type = 'BUCKET' AND saleable_quantity > 0 AND (status IS NULL OR status <> 'ACTIVE')")
    int activatePositiveBuckets(@Param("activityId") Long activityId,
                                @Param("skuId") Long skuId);

    @Update("UPDATE seckill_stock_bucket SET status = 'EMPTY', updated_at = NOW() WHERE activity_id = #{activityId} AND sku_id = #{skuId} AND bucket_type = 'BUCKET' AND saleable_quantity <= 0 AND status = 'ACTIVE'")
    int markActiveNonPositiveBucketsEmpty(@Param("activityId") Long activityId,
                                          @Param("skuId") Long skuId);

    @Select("SELECT bucket_no FROM seckill_stock_bucket WHERE activity_id = #{activityId} AND sku_id = #{skuId} AND bucket_type = 'BUCKET' AND status = 'ACTIVE' AND saleable_quantity > 0 ORDER BY bucket_no ASC")
    List<Integer> selectActivePositiveBucketNos(@Param("activityId") Long activityId,
                                                @Param("skuId") Long skuId);

    @Select("SELECT * FROM seckill_stock_bucket WHERE activity_id = #{activityId} AND sku_id = #{skuId} AND bucket_type = 'BUCKET' AND saleable_quantity <= #{lowWatermark} ORDER BY saleable_quantity ASC, bucket_no ASC LIMIT 1")
    SeckillStockBucketEntity selectAutoTransferTarget(@Param("activityId") Long activityId,
                                                      @Param("skuId") Long skuId,
                                                      @Param("lowWatermark") Integer lowWatermark);

    @Select("SELECT * FROM seckill_stock_bucket WHERE activity_id = #{activityId} AND sku_id = #{skuId} AND bucket_type = 'BUCKET' AND status = 'ACTIVE' AND id <> #{excludedBucketId} AND saleable_quantity >= #{transferSize} + #{reserveQuantity} ORDER BY saleable_quantity DESC, id ASC LIMIT 1")
    SeckillStockBucketEntity selectAutoTransferSource(@Param("activityId") Long activityId,
                                                      @Param("skuId") Long skuId,
                                                      @Param("excludedBucketId") Long excludedBucketId,
                                                      @Param("transferSize") Integer transferSize,
                                                      @Param("reserveQuantity") Integer reserveQuantity);

    @Update("UPDATE seckill_stock_bucket SET saleable_quantity = saleable_quantity + #{quantityDelta}, version = version + 1, updated_at = NOW() WHERE activity_id = #{activityId} AND sku_id = #{skuId} AND bucket_type = 'CENTER' AND bucket_no = 0 AND saleable_quantity + #{quantityDelta} >= 0")
    int applyCenterQuantityDelta(@Param("activityId") Long activityId,
                                 @Param("skuId") Long skuId,
                                 @Param("quantityDelta") Integer quantityDelta);

    @Select("SELECT saleable_quantity AS stock, version FROM seckill_stock_bucket WHERE activity_id = #{activityId} AND sku_id = #{skuId} AND bucket_type = 'CENTER' AND bucket_no = 0 AND shard_key = 0")
    StockVersion selectCenterStockVersion(@Param("activityId") Long activityId,
                                          @Param("skuId") Long skuId);
}

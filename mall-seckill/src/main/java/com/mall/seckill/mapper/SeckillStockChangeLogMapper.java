package com.mall.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.seckill.pojo.entity.SeckillStockChangeLogEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SeckillStockChangeLogMapper extends BaseMapper<SeckillStockChangeLogEntity> {

    @Select("SELECT * FROM seckill_stock_change_log WHERE status = #{status} ORDER BY id LIMIT #{limit}")
    List<SeckillStockChangeLogEntity> selectByStatusForConsume(@Param("status") String status,
                                                               @Param("limit") Integer limit);

    @Select("SELECT * FROM seckill_stock_change_log WHERE bucket_shard_key = #{bucketShardKey} AND status = #{status} ORDER BY id LIMIT #{limit}")
    List<SeckillStockChangeLogEntity> selectByStatusForConsumeByShard(@Param("bucketShardKey") Long bucketShardKey,
                                                                       @Param("status") String status,
                                                                       @Param("limit") Integer limit);

    @Select("SELECT COUNT(1) FROM seckill_stock_change_log WHERE request_id = #{requestId} AND bucket_shard_key = #{bucketShardKey}")
    long countByRequestIdAndBucketShardKey(@Param("requestId") String requestId,
                                           @Param("bucketShardKey") Long bucketShardKey);

    @Update("UPDATE seckill_stock_change_log SET status = #{nextStatus}, updated_at = NOW() WHERE id = #{id} AND status = #{expectedStatus}")
    int updateStatus(@Param("id") Long id,
                     @Param("expectedStatus") String expectedStatus,
                     @Param("nextStatus") String nextStatus);

    @Update("UPDATE seckill_stock_change_log SET status = #{nextStatus}, updated_at = NOW() WHERE id = #{id} AND bucket_shard_key = #{bucketShardKey} AND status = #{expectedStatus}")
    int updateStatusByShard(@Param("id") Long id,
                            @Param("bucketShardKey") Long bucketShardKey,
                            @Param("expectedStatus") String expectedStatus,
                            @Param("nextStatus") String nextStatus);

    @Update({
            "<script>",
            "UPDATE seckill_stock_change_log",
            "SET status = #{nextStatus}, updated_at = NOW()",
            "WHERE status = #{expectedStatus}",
            "AND id IN",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>",
            "#{id}",
            "</foreach>",
            "</script>"
    })
    int updateStatusByIds(@Param("ids") List<Long> ids,
                          @Param("expectedStatus") String expectedStatus,
                          @Param("nextStatus") String nextStatus);

    @Update({
            "<script>",
            "UPDATE seckill_stock_change_log",
            "SET status = #{nextStatus}, updated_at = NOW()",
            "WHERE bucket_shard_key = #{bucketShardKey}",
            "AND status = #{expectedStatus}",
            "AND id IN",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>",
            "#{id}",
            "</foreach>",
            "</script>"
    })
    int updateStatusByIdsAndShard(@Param("ids") List<Long> ids,
                                  @Param("bucketShardKey") Long bucketShardKey,
                                  @Param("expectedStatus") String expectedStatus,
                                  @Param("nextStatus") String nextStatus);
}

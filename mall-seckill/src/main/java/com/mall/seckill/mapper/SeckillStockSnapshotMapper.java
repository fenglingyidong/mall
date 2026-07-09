package com.mall.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.seckill.pojo.entity.SeckillStockSnapshotEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SeckillStockSnapshotMapper extends BaseMapper<SeckillStockSnapshotEntity> {

    @Update("UPDATE seckill_stock_snapshot SET bucket_id = #{snapshot.bucketId}, bucket_no = #{snapshot.bucketNo}, bucket_shard_key = #{snapshot.bucketShardKey}, strategy_version = #{snapshot.strategyVersion}, change_id = #{snapshot.changeId}, updated_at = #{snapshot.updatedAt} WHERE request_id = #{snapshot.requestId} AND bucket_shard_key = #{snapshot.bucketShardKey}")
    int updateBucketDeductionByRequestAndShardKey(@Param("snapshot") SeckillStockSnapshotEntity snapshot);

    @Update("UPDATE seckill_stock_snapshot SET status = 'FAILED', active_key = NULL, message = #{message}, updated_at = NOW() WHERE request_id = #{requestId} AND status = 'REGISTERED'")
    int releaseActiveKeyIfRegistered(@Param("requestId") String requestId, @Param("message") String message);

    @Select("SELECT * FROM seckill_stock_snapshot WHERE status = 'REGISTERED' AND created_at < #{before} ORDER BY created_at, request_id LIMIT #{limit}")
    List<SeckillStockSnapshotEntity> findRegisteredBefore(@Param("before") LocalDateTime before,
                                                          @Param("limit") Integer limit);
}

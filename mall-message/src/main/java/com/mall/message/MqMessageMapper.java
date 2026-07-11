package com.mall.message;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface MqMessageMapper extends BaseMapper<MqMessageEntity> {

    @Insert({
            "<script>",
            "INSERT IGNORE INTO mq_message",
            "(message_id, exchange_name, routing_key, business_key, payload, bucket_shard_key, delay_millis, status, created_at, updated_at)",
            "VALUES",
            "<foreach collection='list' item='entity' separator=','>",
            "(#{entity.messageId}, #{entity.exchangeName}, #{entity.routingKey}, #{entity.businessKey}, #{entity.payload},",
            "#{entity.bucketShardKey}, #{entity.delayMillis}, #{entity.status}, #{entity.createdAt}, #{entity.updatedAt})",
            "</foreach>",
            "</script>"
    })
    int insertIgnoreBatch(List<MqMessageEntity> entities);
}

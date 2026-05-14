package com.mall.message;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mq_message")
public class MqMessageEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String messageId;

    private String exchangeName;

    private String routingKey;

    private String businessKey;

    private String payload;

    private Long delayMillis;

    private String status;

    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

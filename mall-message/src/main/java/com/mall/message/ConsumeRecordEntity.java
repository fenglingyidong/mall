package com.mall.message;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("consume_record")
public class ConsumeRecordEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String messageId;

    private LocalDateTime consumedAt;
}

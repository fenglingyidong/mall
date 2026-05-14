package com.mall.product.pojo.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("spu")
public class SpuEntity {

    @TableId
    private Long id;

    private String name;

    private Long categoryId;

    private Long brandId;
}

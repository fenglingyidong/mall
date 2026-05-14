package com.mall.product.pojo.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("category")
public class CategoryEntity {

    @TableId
    private Long id;

    private Long parentId;

    private String name;
}

package com.mall.product.pojo.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sku_stock")
public class SkuStockEntity {

    @TableId
    private Long skuId;

    private Integer stock;

    private Integer lockedStock;
}

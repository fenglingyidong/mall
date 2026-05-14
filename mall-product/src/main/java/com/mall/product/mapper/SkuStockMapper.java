package com.mall.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.product.pojo.entity.SkuStockEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface SkuStockMapper extends BaseMapper<SkuStockEntity> {

    @Update("UPDATE sku_stock SET stock = stock - #{quantity} WHERE sku_id = #{skuId} AND stock >= #{quantity}")
    int deductStock(@Param("skuId") Long skuId, @Param("quantity") Integer quantity);

    @Update("""
            UPDATE sku_stock
            SET stock = stock - #{quantity}, locked_stock = locked_stock + #{quantity}
            WHERE sku_id = #{skuId} AND stock >= #{quantity}
            """)
    int reserveStock(@Param("skuId") Long skuId, @Param("quantity") Integer quantity);

    @Update("""
            UPDATE sku_stock
            SET locked_stock = locked_stock - #{quantity}
            WHERE sku_id = #{skuId} AND locked_stock >= #{quantity}
            """)
    int confirmReservedStock(@Param("skuId") Long skuId, @Param("quantity") Integer quantity);

    @Update("""
            UPDATE sku_stock
            SET stock = stock + #{quantity}, locked_stock = locked_stock - #{quantity}
            WHERE sku_id = #{skuId} AND locked_stock >= #{quantity}
            """)
    int cancelReservedStock(@Param("skuId") Long skuId, @Param("quantity") Integer quantity);

    @Update("UPDATE sku_stock SET stock = stock + #{quantity} WHERE sku_id = #{skuId}")
    int releaseStock(@Param("skuId") Long skuId, @Param("quantity") Integer quantity);
}

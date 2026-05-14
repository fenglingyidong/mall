package com.mall.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.product.pojo.entity.ProductDetailRow;
import com.mall.product.pojo.entity.SkuEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SkuMapper extends BaseMapper<SkuEntity> {

    @Select("""
            SELECT
                s.id AS sku_id,
                s.spu_id AS spu_id,
                s.name AS sku_name,
                sp.name AS spu_name,
                c.name AS category_name,
                b.name AS brand_name,
                s.price AS price,
                COALESCE(ss.stock, 0) AS stock
            FROM sku s
            JOIN spu sp ON s.spu_id = sp.id
            LEFT JOIN category c ON sp.category_id = c.id
            LEFT JOIN brand b ON sp.brand_id = b.id
            LEFT JOIN sku_stock ss ON s.id = ss.sku_id
            WHERE s.id = #{skuId}
            """)
    ProductDetailRow selectDetail(@Param("skuId") Long skuId);
}

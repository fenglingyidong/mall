package com.mall.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.product.pojo.entity.ProductDetailRow;
import com.mall.product.pojo.entity.SkuEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

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

    @Select("""
            <script>
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
            <where>
                <if test="keyword != null and keyword.trim() != ''">
                    AND (
                        s.name LIKE CONCAT('%', #{keyword}, '%')
                        OR sp.name LIKE CONCAT('%', #{keyword}, '%')
                        OR c.name LIKE CONCAT('%', #{keyword}, '%')
                        OR b.name LIKE CONCAT('%', #{keyword}, '%')
                    )
                </if>
                <if test="categoryId != null">
                    AND sp.category_id = #{categoryId}
                </if>
                <if test="brand != null and brand.trim() != ''">
                    AND b.name LIKE CONCAT('%', #{brand}, '%')
                </if>
                <if test="minPrice != null">
                    AND s.price &gt;= #{minPrice}
                </if>
                <if test="maxPrice != null">
                    AND s.price &lt;= #{maxPrice}
                </if>
            </where>
            ORDER BY s.price ASC, s.id ASC
            LIMIT #{limit}
            </script>
            """)
    List<ProductDetailRow> selectSearch(@Param("keyword") String keyword,
                                        @Param("categoryId") Long categoryId,
                                        @Param("brand") String brand,
                                        @Param("minPrice") BigDecimal minPrice,
                                        @Param("maxPrice") BigDecimal maxPrice,
                                        @Param("limit") int limit);

    @Select("""
            <script>
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
            <where>
                <if test="name != null and name.trim() != ''">
                    AND (
                        s.name LIKE CONCAT('%', #{name}, '%')
                        OR sp.name LIKE CONCAT('%', #{name}, '%')
                    )
                </if>
            </where>
            ORDER BY s.price ASC, s.id ASC
            LIMIT #{limit}
            </script>
            """)
    List<ProductDetailRow> selectSearchByName(@Param("name") String name,
                                              @Param("limit") int limit);
}

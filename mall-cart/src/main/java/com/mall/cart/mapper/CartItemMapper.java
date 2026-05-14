package com.mall.cart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.cart.pojo.entity.CartItemEntity;
import org.apache.ibatis.annotations.Insert;

public interface CartItemMapper extends BaseMapper<CartItemEntity> {

    @Insert("""
            INSERT INTO cart_item (user_id, sku_id, sku_name, price, quantity, checked)
            VALUES (#{userId}, #{skuId}, #{skuName}, #{price}, #{quantity}, #{checked})
            ON DUPLICATE KEY UPDATE
                sku_name = VALUES(sku_name),
                price = VALUES(price),
                quantity = VALUES(quantity),
                checked = VALUES(checked)
            """)
    void upsert(CartItemEntity entity);
}

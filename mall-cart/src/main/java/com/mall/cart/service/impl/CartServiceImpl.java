package com.mall.cart.service.impl;

import com.mall.cart.mapper.CartMapper;
import com.mall.cart.pojo.dto.AddCartItemRequest;
import com.mall.cart.pojo.dto.UpdateCartItemRequest;
import com.mall.cart.pojo.entity.CartItem;
import com.mall.cart.service.CartService;
import com.mall.common.context.UserContext;
import com.mall.common.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

@Service
public class CartServiceImpl implements CartService {

    private final CartMapper cartMapper;

    public CartServiceImpl(CartMapper cartMapper) {
        this.cartMapper = cartMapper;
    }

    @Override
    public List<CartItem> list() {
        Long userId = userId();
        List<CartItem> items = cartMapper.findAll(userId);
        items.sort(Comparator.comparing(CartItem::skuId));
        return items;
    }

    @Override
    public CartItem add(AddCartItemRequest request) {
        Long userId = userId();
        CartItem old = cartMapper.findOne(userId, request.skuId());
        CartItem item;
        if (old == null) {
            item = new CartItem(
                    request.skuId(),
                    request.skuName() == null ? "SKU-" + request.skuId() : request.skuName(),
                    request.price() == null ? BigDecimal.ZERO : request.price(),
                    request.quantity(),
                    true
            );
        } else {
            item = old.withQuantity(old.quantity() + request.quantity());
        }
        cartMapper.save(userId, item);
        return item;
    }

    @Override
    public CartItem update(Long skuId, UpdateCartItemRequest request) {
        Long userId = userId();
        CartItem old = cartMapper.findOne(userId, skuId);
        if (old == null) {
            throw new BusinessException(404, "Cart item not found");
        }
        CartItem item = old;
        if (request.quantity() != null) {
            item = item.withQuantity(request.quantity());
        }
        if (request.checked() != null) {
            item = item.withChecked(request.checked());
        }
        cartMapper.save(userId, item);
        return item;
    }

    @Override
    public void remove(Long skuId) {
        cartMapper.delete(userId(), skuId);
    }

    @Override
    public List<CartItem> selected(Long userId) {
        return cartMapper.findAll(userId).stream().filter(item -> Boolean.TRUE.equals(item.checked())).toList();
    }

    @Override
    public void clearSelected(Long userId) {
        selected(userId).forEach(item -> cartMapper.delete(userId, item.skuId()));
    }

    private Long userId() {
        return UserContext.currentUserIdOrDefault(1L);
    }
}

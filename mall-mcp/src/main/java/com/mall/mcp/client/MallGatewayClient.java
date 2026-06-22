package com.mall.mcp.client;

import com.mall.mcp.model.AddCartItemRequest;
import com.mall.mcp.model.CartItem;
import com.mall.mcp.model.ConfirmOrderResponse;
import com.mall.mcp.model.CreateOrderRequest;
import com.mall.mcp.model.LoginResponse;
import com.mall.mcp.model.OrderInfo;
import com.mall.mcp.model.ProductDetail;
import com.mall.mcp.model.ProductSearchItem;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface MallGatewayClient {

    List<ProductSearchItem> searchProducts(String keyword,
                                           Long categoryId,
                                           String brand,
                                           BigDecimal minPrice,
                                           BigDecimal maxPrice,
                                           Integer limit);

    ProductDetail getProductDetail(Long skuId);

    Optional<LoginResponse> login(String username, String password);

    CartItem addToCart(String authorization, AddCartItemRequest request);

    List<CartItem> viewCart(String authorization);

    ConfirmOrderResponse confirmOrder(String authorization);

    OrderInfo createOrder(String authorization, CreateOrderRequest request);
}

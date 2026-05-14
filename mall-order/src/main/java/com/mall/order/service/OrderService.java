package com.mall.order.service;

import com.mall.order.pojo.dto.CreateOrderRequest;
import com.mall.order.pojo.dto.SeckillOrderRequest;
import com.mall.order.pojo.entity.OrderInfo;
import com.mall.order.pojo.vo.ConfirmOrderResponse;

public interface OrderService {

    ConfirmOrderResponse confirm();

    OrderInfo create(CreateOrderRequest request);

    OrderInfo get(String orderSn);

    OrderInfo cancel(String orderSn);

    OrderInfo pay(String orderSn);

    OrderInfo closeIfCreated(String orderSn);

    OrderInfo createSeckillOrder(SeckillOrderRequest request);
}

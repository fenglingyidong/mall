package com.mall.order.controller;

import com.mall.common.api.ApiResponse;
import com.mall.order.pojo.dto.CreateOrderRequest;
import com.mall.order.pojo.dto.SeckillOrderRequest;
import com.mall.order.pojo.entity.OrderInfo;
import com.mall.order.pojo.vo.ConfirmOrderResponse;
import com.mall.order.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {

    @Autowired
    private OrderService orderService;

    @PostMapping("/api/order/confirm")
    public ApiResponse<ConfirmOrderResponse> confirm() {
        return ApiResponse.success(orderService.confirm());
    }

    @PostMapping("/api/order/create")
    public ApiResponse<OrderInfo> create(@Valid @RequestBody(required = false) CreateOrderRequest request) {
        return ApiResponse.success(orderService.create(request == null ? new CreateOrderRequest(null) : request));
    }

    @GetMapping("/api/order/{orderSn}")
    public ApiResponse<OrderInfo> get(@PathVariable String orderSn) {
        return ApiResponse.success(orderService.get(orderSn));
    }

    @PostMapping("/api/order/{orderSn}/cancel")
    public ApiResponse<OrderInfo> cancel(@PathVariable String orderSn) {
        return ApiResponse.success(orderService.cancel(orderSn));
    }

    @PostMapping("/api/order/{orderSn}/pay")
    public ApiResponse<OrderInfo> pay(@PathVariable String orderSn) {
        return ApiResponse.success(orderService.pay(orderSn));
    }

    @PostMapping("/internal/order/close/{orderSn}")
    public ApiResponse<OrderInfo> close(@PathVariable String orderSn) {
        return ApiResponse.success(orderService.closeIfCreated(orderSn));
    }

    @PostMapping("/internal/order/seckill")
    public ApiResponse<OrderInfo> createSeckill(@Valid @RequestBody SeckillOrderRequest request) {
        return ApiResponse.success(orderService.createSeckillOrder(request));
    }
}

package com.mall.order.service.impl;

import com.mall.common.api.ApiResponse;
import com.mall.common.context.UserContext;
import com.mall.common.exception.BusinessException;
import com.mall.common.model.OrderStatus;
import com.mall.common.util.OrderNoGenerator;
import com.mall.message.ConsumeRecordRepository;
import com.mall.message.ReliableMessagePublisher;
import com.mall.order.config.OrderProperties;
import com.mall.order.mapper.CartClient;
import com.mall.order.mapper.OrderRepository;
import com.mall.order.mapper.ProductClient;
import com.mall.order.pojo.dto.CreateOrderRequest;
import com.mall.order.pojo.dto.SeckillOrderRequest;
import com.mall.order.pojo.dto.StockDeductRequest;
import com.mall.order.pojo.entity.OrderInfo;
import com.mall.order.pojo.entity.OrderItem;
import com.mall.order.pojo.vo.CartItemView;
import com.mall.order.pojo.vo.ConfirmOrderResponse;
import com.mall.order.pojo.vo.ProductSkuView;
import com.mall.order.service.OrderService;
import io.seata.spring.annotation.GlobalTransactional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class OrderServiceImpl implements OrderService {

    private final CartClient cartClient;
    private final ProductClient productClient;
    private final OrderRepository repository;
    private final ReliableMessagePublisher messagePublisher;
    private final ConsumeRecordRepository consumeRecordRepository;
    private final OrderProperties properties;

    public OrderServiceImpl(CartClient cartClient,
                            ProductClient productClient,
                            OrderRepository repository,
                            ReliableMessagePublisher messagePublisher,
                            ConsumeRecordRepository consumeRecordRepository,
                            OrderProperties properties) {
        this.cartClient = cartClient;
        this.productClient = productClient;
        this.repository = repository;
        this.messagePublisher = messagePublisher;
        this.consumeRecordRepository = consumeRecordRepository;
        this.properties = properties;
    }

    @Override
    public ConfirmOrderResponse confirm() {
        Long userId = UserContext.currentUserIdOrDefault(1L);
        List<CartItemView> selected = cartClient.selected(userId);
        if (selected == null || selected.isEmpty()) {
            throw new BusinessException(400, "No selected cart items");
        }
        List<OrderItem> items = selected.stream().map(item -> {
            ProductSkuView sku = productClient.detail(item.skuId());
            BigDecimal amount = sku.price().multiply(BigDecimal.valueOf(item.quantity()));
            return new OrderItem(sku.skuId(), sku.skuName(), sku.price(), item.quantity(), amount);
        }).toList();
        BigDecimal total = items.stream().map(OrderItem::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ConfirmOrderResponse(userId, items, total);
    }

    @Override
    @GlobalTransactional(name = "normal-order-create", rollbackFor = Exception.class)
    @Transactional(rollbackFor = Exception.class)
    public OrderInfo create(CreateOrderRequest request) {
        ConfirmOrderResponse confirm = confirm();
        List<StockDeductRequest.Item> stockItems = confirm.items().stream()
                .map(item -> new StockDeductRequest.Item(item.skuId(), item.quantity()))
                .toList();
        requireSuccess(productClient.deduct(stockItems), "Stock TCC prepare");
        OrderInfo order = new OrderInfo(
                OrderNoGenerator.next("M"),
                confirm.userId(),
                OrderStatus.CREATED,
                confirm.totalAmount(),
                confirm.items(),
                "NORMAL",
                Instant.now(),
                Instant.now()
        );
        repository.save(order);
        cartClient.clearSelected(confirm.userId());
        publishCloseMessage(order.orderSn());
        return order;
    }

    @Override
    public OrderInfo get(String orderSn) {
        return repository.require(orderSn);
    }

    @Override
    public OrderInfo cancel(String orderSn) {
        OrderInfo updated = repository.transition(orderSn, OrderStatus.CREATED, OrderStatus.CANCELED);
        if (updated.status() == OrderStatus.CANCELED) {
            releaseStock(updated);
        }
        return updated;
    }

    @Override
    public OrderInfo pay(String orderSn) {
        return repository.transition(orderSn, OrderStatus.CREATED, OrderStatus.PAID);
    }

    @Override
    public OrderInfo closeIfCreated(String orderSn) {
        OrderInfo before = repository.require(orderSn);
        OrderInfo updated = repository.transition(orderSn, OrderStatus.CREATED, OrderStatus.CLOSED);
        if (before.status() == OrderStatus.CREATED && updated.status() == OrderStatus.CLOSED) {
            releaseStock(updated);
        }
        return updated;
    }

    @Override
    public OrderInfo createSeckillOrder(SeckillOrderRequest request) {
        if (!consumeRecordRepository.markIfAbsent(request.requestId())) {
            return repository.findSeckillOrder(request.activityId(), request.userId())
                    .orElseThrow(() -> new BusinessException(409, "Seckill message already consumed"));
        }
        return repository.findSeckillOrder(request.activityId(), request.userId()).orElseGet(() -> {
            List<OrderItem> items = List.of(new OrderItem(
                    request.skuId(),
                    request.skuName(),
                    request.price(),
                    request.quantity(),
                    request.price().multiply(BigDecimal.valueOf(request.quantity()))
            ));
            requireSuccess(productClient.deduct(items.stream()
                    .map(item -> new StockDeductRequest.Item(item.skuId(), item.quantity()))
                    .toList()), "Stock deduct");
            OrderInfo order = new OrderInfo(
                    OrderNoGenerator.next("S"),
                    request.userId(),
                    OrderStatus.CREATED,
                    items.get(0).amount(),
                    items,
                    "SECKILL",
                    Instant.now(),
                    Instant.now()
            );
            repository.bindSeckill(request.activityId(), request.userId(), request.skuId(), order.orderSn());
            repository.save(order);
            publishCloseMessage(order.orderSn());
            return order;
        });
    }

    private void publishCloseMessage(String orderSn) {
        messagePublisher.publishOrderCloseDelay(orderSn, properties.getOrder().getCloseDelaySeconds(), TimeUnit.SECONDS);
    }

    private void releaseStock(OrderInfo order) {
        List<StockDeductRequest.Item> items = order.items().stream()
                .map(item -> new StockDeductRequest.Item(item.skuId(), item.quantity()))
                .toList();
        requireSuccess(productClient.release(items), "Stock release");
    }

    private void requireSuccess(ApiResponse<Void> response, String operation) {
        if (response == null) {
            throw new BusinessException(503, operation + " failed");
        }
        if (response.code() != 0) {
            throw new BusinessException(response.code(), operation + " failed: " + response.message());
        }
    }
}

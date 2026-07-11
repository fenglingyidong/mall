package com.mall.order.service.impl;

import com.mall.order.config.OrderProperties;
import com.mall.order.mapper.OrderRepository;
import com.mall.order.pojo.entity.OrderInfo;
import com.mall.order.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "mall.order.expired-close", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OrderExpiredCloseJob {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiredCloseJob.class);

    private final OrderRepository repository;
    private final OrderService orderService;
    private final OrderProperties properties;

    public OrderExpiredCloseJob(OrderRepository repository,
                                OrderService orderService,
                                OrderProperties properties) {
        this.repository = repository;
        this.orderService = orderService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${mall.order.expired-close.fixed-delay:5000}")
    public void closeExpired() {
        int batchSize = Math.max(1, properties.getOrder().getExpiredClose().getBatchSize());
        List<OrderInfo> expiredOrders = repository.findExpiredSeckillCreatedOrders(Instant.now(), batchSize);
        for (OrderInfo order : expiredOrders) {
            try {
                orderService.closeIfCreated(order.orderSn());
            } catch (RuntimeException exception) {
                log.warn("Failed to close expired seckill order, orderSn={}", order.orderSn(), exception);
            }
        }
    }
}

package com.mall.order.service.impl;

import com.mall.common.model.OrderStatus;
import com.mall.order.config.OrderProperties;
import com.mall.order.mapper.OrderRepository;
import com.mall.order.pojo.entity.OrderInfo;
import com.mall.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderExpiredCloseJobTest {

    @Mock
    private OrderRepository repository;

    @Mock
    private OrderService orderService;

    @Test
    void shouldCloseExpiredSeckillOrdersByConfiguredBatchSize() {
        OrderProperties properties = new OrderProperties();
        properties.getOrder().getExpiredClose().setBatchSize(500);
        OrderInfo order = new OrderInfo(
                "S1",
                101L,
                OrderStatus.CREATED,
                BigDecimal.valueOf(99),
                List.of(),
                "SECKILL",
                "r1",
                Instant.now().minusSeconds(1),
                Instant.now().minusSeconds(60),
                Instant.now().minusSeconds(60));
        when(repository.findExpiredSeckillCreatedOrders(any(Instant.class), eq(500)))
                .thenReturn(List.of(order));

        new OrderExpiredCloseJob(repository, orderService, properties).closeExpired();

        verify(orderService).closeIfCreated("S1");
    }

    @Test
    void shouldSkipWhenNoExpiredOrdersFound() {
        OrderProperties properties = new OrderProperties();
        when(repository.findExpiredSeckillCreatedOrders(any(Instant.class), eq(500)))
                .thenReturn(List.of());

        new OrderExpiredCloseJob(repository, orderService, properties).closeExpired();

        verify(orderService, never()).closeIfCreated("S1");
    }
}

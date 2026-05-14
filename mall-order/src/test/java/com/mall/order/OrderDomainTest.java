package com.mall.order;

import com.mall.common.model.OrderStatus;
import com.mall.order.pojo.entity.OrderInfo;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderDomainTest {

    @Test
    void shouldCreateNewInstanceWhenStatusChanged() {
        OrderInfo order = new OrderInfo("M1", 1L, OrderStatus.CREATED, BigDecimal.TEN,
                List.of(), "NORMAL", Instant.now(), Instant.now());

        OrderInfo paid = order.withStatus(OrderStatus.PAID);

        assertThat(paid.status()).isEqualTo(OrderStatus.PAID);
        assertThat(order.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(paid.orderSn()).isEqualTo(order.orderSn());
    }
}

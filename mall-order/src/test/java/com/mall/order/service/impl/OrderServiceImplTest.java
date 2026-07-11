package com.mall.order.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.exception.BusinessException;
import com.mall.common.model.OrderStatus;
import com.mall.message.ConsumeRecordRepository;
import com.mall.message.ReliableMessagePublisher;
import com.mall.order.config.OrderProperties;
import com.mall.order.mapper.CartClient;
import com.mall.order.mapper.OrderRepository;
import com.mall.order.mapper.ProductClient;
import com.mall.order.pojo.dto.SeckillOrderRequest;
import com.mall.order.pojo.entity.OrderInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock
    private CartClient cartClient;
    @Mock
    private ProductClient productClient;
    @Mock
    private OrderRepository repository;
    @Mock
    private ReliableMessagePublisher messagePublisher;
    @Mock
    private ConsumeRecordRepository consumeRecordRepository;

    @Test
    void createSeckillOrderShouldUseSingleLocalTransaction() throws Exception {
        assertThat(AnnotatedElementUtils.hasAnnotation(
                OrderServiceImpl.class.getMethod("createSeckillOrder", SeckillOrderRequest.class),
                Transactional.class)).isTrue();
    }

    @Test
    void createSeckillOrderShouldSetPayExpireAtAndSkipCloseDelayMessage() {
        OrderProperties properties = new OrderProperties();
        properties.getOrder().setCloseDelaySeconds(123);
        OrderServiceImpl service = new OrderServiceImpl(
                cartClient,
                productClient,
                repository,
                messagePublisher,
                consumeRecordRepository,
                properties,
                new ObjectMapper());
        SeckillOrderRequest request = new SeckillOrderRequest(
                "r1",
                1L,
                101L,
                1001L,
                "phone",
                BigDecimal.valueOf(99),
                1,
                3L);
        when(consumeRecordRepository.markIfAbsent("r1")).thenReturn(true);
        when(repository.findBySource("SECKILL", "r1")).thenReturn(Optional.empty());

        OrderInfo order = service.createSeckillOrder(request);
        ArgumentCaptor<OrderInfo> orderCaptor = ArgumentCaptor.forClass(OrderInfo.class);

        verify(repository).bindSeckill(eq("r1"), eq(1L), eq(101L), eq(1001L), eq(order.orderSn()), eq(3L));
        verify(repository).save(orderCaptor.capture());
        OrderInfo savedOrder = orderCaptor.getValue();
        assertThat(savedOrder.payExpireAt()).isNotNull();
        assertThat(Duration.between(savedOrder.createdAt(), savedOrder.payExpireAt()).toSeconds()).isEqualTo(123L);
        verify(messagePublisher, never()).enqueueOrderCloseDelay(eq(order.orderSn()), eq(123L), eq(TimeUnit.SECONDS));
        verify(messagePublisher, never()).publishOrderCloseDelay(anyString(), anyLong(), any(TimeUnit.class));
        verify(messagePublisher).enqueueSeckillOrderResult(eq("r1"), contains("\"status\":\"SUCCESS\""), eq(3L));
    }

    @Test
    void payShouldRejectExpiredSeckillOrder() {
        OrderProperties properties = new OrderProperties();
        OrderServiceImpl service = new OrderServiceImpl(
                cartClient,
                productClient,
                repository,
                messagePublisher,
                consumeRecordRepository,
                properties,
                new ObjectMapper());
        OrderInfo expired = new OrderInfo(
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
        when(repository.require("S1")).thenReturn(expired);

        assertThatThrownBy(() -> service.pay("S1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("expired");

        verify(repository, never()).transition(eq("S1"), eq(OrderStatus.CREATED), eq(OrderStatus.PAID));
    }
}

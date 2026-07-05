package com.mall.order.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.exception.BusinessException;
import com.mall.message.ReliableMessageRepository;
import com.mall.message.ReliableMessagePublisher;
import com.mall.order.pojo.dto.SeckillOrderRequest;
import com.mall.order.service.OrderService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderMessageListenerTest {

    @Mock
    private OrderService orderService;

    @Mock
    private ReliableMessagePublisher messagePublisher;

    @Mock
    private ReliableMessageRepository messageRepository;

    @Mock
    private Channel channel;

    @Test
    void shouldRequeueWhenCreatedSeckillOrderIsTemporarilyInvisible() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OrderMessageListener listener = newListener(objectMapper);
        SeckillOrderRequest request = request();
        when(orderService.createSeckillOrder(request)).thenThrow(new BusinessException(404, "Order not found"));

        listener.onSeckillOrderCreate(objectMapper.writeValueAsString(request), message(), channel);

        verify(messagePublisher, never()).publishSeckillOrderResult(anyString(), anyString());
        verify(messageRepository, never()).markConsumed(anyString());
        verify(channel).basicNack(7L, false, true);
    }

    @Test
    void shouldPublishFailedResultForNonRetryableSeckillOrderFailure() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OrderMessageListener listener = newListener(objectMapper);
        SeckillOrderRequest request = request();
        when(orderService.createSeckillOrder(request)).thenThrow(new BusinessException(400, "bad request"));

        listener.onSeckillOrderCreate(objectMapper.writeValueAsString(request), message(), channel);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(messagePublisher).publishSeckillOrderResult(anyString(), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).contains("\"status\":\"FAILED\"");
        assertThat(payloadCaptor.getValue()).contains("\"message\":\"bad request\"");
        verify(channel).basicNack(7L, false, false);
    }

    private OrderMessageListener newListener(ObjectMapper objectMapper) {
        OrderMessageListener listener = new OrderMessageListener();
        ReflectionTestUtils.setField(listener, "orderService", orderService);
        ReflectionTestUtils.setField(listener, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(listener, "messagePublisher", messagePublisher);
        ReflectionTestUtils.setField(listener, "messageRepository", messageRepository);
        return listener;
    }

    private SeckillOrderRequest request() {
        return new SeckillOrderRequest("r1", 1L, 101L, 1001L, "phone", BigDecimal.valueOf(99), 1);
    }

    private Message message() {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(7L);
        properties.setMessageId("m1");
        return new Message(new byte[0], properties);
    }
}

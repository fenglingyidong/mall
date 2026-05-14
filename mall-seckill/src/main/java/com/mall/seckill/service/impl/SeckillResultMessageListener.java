package com.mall.seckill.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.message.MessageNames;
import com.mall.message.ReliableMessageRepository;
import com.mall.message.SeckillOrderResultMessage;
import com.mall.seckill.mapper.SeckillRepository;
import com.mall.seckill.pojo.vo.SeckillResult;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class SeckillResultMessageListener {

    private final SeckillRepository repository;
    private final ObjectMapper objectMapper;
    private final ReliableMessageRepository messageRepository;

    public SeckillResultMessageListener(SeckillRepository repository,
                                        ObjectMapper objectMapper,
                                        ReliableMessageRepository messageRepository) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.messageRepository = messageRepository;
    }

    @RabbitListener(queues = MessageNames.SECKILL_ORDER_RESULT_QUEUE)
    public void onSeckillOrderResult(String payload, Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            SeckillOrderResultMessage resultMessage = objectMapper.readValue(payload, SeckillOrderResultMessage.class);
            repository.saveResult(new SeckillResult(
                    resultMessage.requestId(),
                    resultMessage.status(),
                    resultMessage.orderSn(),
                    resultMessage.message()
            ));
            markConsumed(message);
            channel.basicAck(deliveryTag, false);
        } catch (Exception exception) {
            channel.basicNack(deliveryTag, false, false);
        }
    }

    private void markConsumed(Message message) {
        String messageId = message.getMessageProperties().getMessageId();
        if (messageId != null) {
            messageRepository.markConsumed(messageId);
        }
    }
}

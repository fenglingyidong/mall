package com.mall.seckill.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.message.MessageNames;
import com.mall.message.ReliableMessageRepository;
import com.mall.message.SeckillOrderResultMessage;
import com.mall.seckill.cache.SeckillStockCache;
import com.mall.seckill.mapper.SeckillRepository;
import com.mall.seckill.pojo.vo.SeckillResult;
import com.mall.seckill.pojo.vo.StockReleaseResult;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class SeckillResultMessageListener {

    private final SeckillRepository repository;
    private final SeckillStockCache stockCache;
    private final ObjectMapper objectMapper;
    private final ReliableMessageRepository messageRepository;

    public SeckillResultMessageListener(SeckillRepository repository,
                                        SeckillStockCache stockCache,
                                        ObjectMapper objectMapper,
                                        ReliableMessageRepository messageRepository) {
        this.repository = repository;
        this.stockCache = stockCache;
        this.objectMapper = objectMapper;
        this.messageRepository = messageRepository;
    }

    @RabbitListener(queues = MessageNames.SECKILL_ORDER_RESULT_QUEUE)
    public void onSeckillOrderResult(String payload, Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            SeckillOrderResultMessage resultMessage = objectMapper.readValue(payload, SeckillOrderResultMessage.class);
            boolean shouldSaveResult = true;
            if ("SUCCESS".equals(resultMessage.status())) {
                SeckillRepository.StockSnapshot snapshot = repository.confirmDeduction(
                        resultMessage.requestId(),
                        resultMessage.orderSn(),
                        resultMessage.message());
                shouldSaveResult = snapshot == null || "CONFIRMED".equals(snapshot.status());
            } else {
                StockReleaseResult releaseResult = repository.releaseDeduction(resultMessage.requestId(), resultMessage.message());
                SeckillRepository.StockSnapshot snapshot = releaseResult == null ? null : releaseResult.snapshot();
                if (snapshot != null && "RELEASED".equals(snapshot.status())) {
                    stockCache.refresh(snapshot.activityId(), snapshot.skuId(), releaseResult.stockVersion());
                }
                shouldSaveResult = snapshot == null || "RELEASED".equals(snapshot.status());
            }
            if (shouldSaveResult) {
                repository.saveResult(new SeckillResult(
                        resultMessage.requestId(),
                        resultMessage.status(),
                        resultMessage.orderSn(),
                        resultMessage.message()
                ));
            }
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

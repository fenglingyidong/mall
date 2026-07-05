package com.mall.message;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;

@Configuration
@EnableScheduling
public class RabbitMessageConfig {

    @Value("${spring.rabbitmq.listener.simple.concurrency:1}")
    private int listenerConcurrency;

    @Value("${spring.rabbitmq.listener.simple.max-concurrency:1}")
    private int listenerMaxConcurrency;

    @Value("${spring.rabbitmq.listener.simple.prefetch:1}")
    private int listenerPrefetch;

    @Bean
    public DirectExchange mallExchange() {
        return new DirectExchange(MessageNames.MALL_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange mallDelayExchange() {
        return new DirectExchange(MessageNames.MALL_DELAY_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange mallDeadLetterExchange() {
        return new DirectExchange(MessageNames.MALL_DLX, true, false);
    }

    @Bean
    public Queue orderCloseDelayQueue() {
        return new Queue(MessageNames.ORDER_CLOSE_DELAY_QUEUE, true, false, false, Map.of(
                "x-dead-letter-exchange", MessageNames.MALL_EXCHANGE,
                "x-dead-letter-routing-key", MessageNames.ORDER_CLOSE_ROUTING_KEY
        ));
    }

    @Bean
    public Queue orderCloseQueue() {
        return new Queue(MessageNames.ORDER_CLOSE_QUEUE, true, false, false, Map.of(
                "x-dead-letter-exchange", MessageNames.MALL_DLX,
                "x-dead-letter-routing-key", MessageNames.ORDER_CLOSE_DLQ_ROUTING_KEY
        ));
    }

    @Bean
    public Queue orderCloseDeadLetterQueue() {
        return new Queue(MessageNames.ORDER_CLOSE_DLQ, true);
    }

    @Bean
    public Queue seckillOrderCreateQueue() {
        return new Queue(MessageNames.SECKILL_ORDER_CREATE_QUEUE, true, false, false, Map.of(
                "x-dead-letter-exchange", MessageNames.MALL_DLX,
                "x-dead-letter-routing-key", MessageNames.SECKILL_ORDER_CREATE_DLQ_ROUTING_KEY
        ));
    }

    @Bean
    public Queue seckillOrderCreateDeadLetterQueue() {
        return new Queue(MessageNames.SECKILL_ORDER_CREATE_DLQ, true);
    }

    @Bean
    public Queue seckillOrderResultQueue() {
        return new Queue(MessageNames.SECKILL_ORDER_RESULT_QUEUE, true, false, false, Map.of(
                "x-dead-letter-exchange", MessageNames.MALL_DLX,
                "x-dead-letter-routing-key", MessageNames.SECKILL_ORDER_RESULT_DLQ_ROUTING_KEY
        ));
    }

    @Bean
    public Queue seckillOrderResultDeadLetterQueue() {
        return new Queue(MessageNames.SECKILL_ORDER_RESULT_DLQ, true);
    }

    @Bean
    public Binding orderCloseDelayBinding(@Qualifier("orderCloseDelayQueue") Queue orderCloseDelayQueue,
                                          @Qualifier("mallDelayExchange") DirectExchange mallDelayExchange) {
        return BindingBuilder.bind(orderCloseDelayQueue)
                .to(mallDelayExchange)
                .with(MessageNames.ORDER_CLOSE_DELAY_ROUTING_KEY);
    }

    @Bean
    public Binding orderCloseBinding(@Qualifier("orderCloseQueue") Queue orderCloseQueue,
                                     @Qualifier("mallExchange") DirectExchange mallExchange) {
        return BindingBuilder.bind(orderCloseQueue)
                .to(mallExchange)
                .with(MessageNames.ORDER_CLOSE_ROUTING_KEY);
    }

    @Bean
    public Binding orderCloseDeadLetterBinding(@Qualifier("orderCloseDeadLetterQueue") Queue orderCloseDeadLetterQueue,
                                               @Qualifier("mallDeadLetterExchange") DirectExchange mallDeadLetterExchange) {
        return BindingBuilder.bind(orderCloseDeadLetterQueue)
                .to(mallDeadLetterExchange)
                .with(MessageNames.ORDER_CLOSE_DLQ_ROUTING_KEY);
    }

    @Bean
    public Binding seckillOrderCreateBinding(@Qualifier("seckillOrderCreateQueue") Queue seckillOrderCreateQueue,
                                             @Qualifier("mallExchange") DirectExchange mallExchange) {
        return BindingBuilder.bind(seckillOrderCreateQueue)
                .to(mallExchange)
                .with(MessageNames.SECKILL_ORDER_CREATE_ROUTING_KEY);
    }

    @Bean
    public Binding seckillOrderCreateDeadLetterBinding(@Qualifier("seckillOrderCreateDeadLetterQueue") Queue seckillOrderCreateDeadLetterQueue,
                                                       @Qualifier("mallDeadLetterExchange") DirectExchange mallDeadLetterExchange) {
        return BindingBuilder.bind(seckillOrderCreateDeadLetterQueue)
                .to(mallDeadLetterExchange)
                .with(MessageNames.SECKILL_ORDER_CREATE_DLQ_ROUTING_KEY);
    }

    @Bean
    public Binding seckillOrderResultBinding(@Qualifier("seckillOrderResultQueue") Queue seckillOrderResultQueue,
                                             @Qualifier("mallExchange") DirectExchange mallExchange) {
        return BindingBuilder.bind(seckillOrderResultQueue)
                .to(mallExchange)
                .with(MessageNames.SECKILL_ORDER_RESULT_ROUTING_KEY);
    }

    @Bean
    public Binding seckillOrderResultDeadLetterBinding(@Qualifier("seckillOrderResultDeadLetterQueue") Queue seckillOrderResultDeadLetterQueue,
                                                       @Qualifier("mallDeadLetterExchange") DirectExchange mallDeadLetterExchange) {
        return BindingBuilder.bind(seckillOrderResultDeadLetterQueue)
                .to(mallDeadLetterExchange)
                .with(MessageNames.SECKILL_ORDER_RESULT_DLQ_ROUTING_KEY);
    }

    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(messageConverter);
        rabbitTemplate.setMandatory(true);
        return rabbitTemplate;
    }

    @Bean
    public ThreadPoolTaskExecutor reliableMessageDispatchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("reliable-message-dispatch-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(10000);
        executor.initialize();
        return executor;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(ConnectionFactory connectionFactory,
                                                                              MessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setDefaultRequeueRejected(false);
        factory.setConcurrentConsumers(Math.max(1, listenerConcurrency));
        factory.setMaxConcurrentConsumers(Math.max(Math.max(1, listenerConcurrency), listenerMaxConcurrency));
        factory.setPrefetchCount(Math.max(1, listenerPrefetch));
        return factory;
    }
}

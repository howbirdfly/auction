package com.auction.backend.auction.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.aopalliance.aop.Advice;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
@ConditionalOnProperty(name = "auction.persistence.rabbitmq.enabled", havingValue = "true")
public class RabbitAuctionPersistenceConfig {

    @Bean
    public DirectExchange auctionHotBidExchange(AuctionAsyncPersistenceProperties properties) {
        return new DirectExchange(properties.getExchange(), true, false);
    }

    @Bean
    public Queue auctionHotBidQueue(AuctionAsyncPersistenceProperties properties) {
        return new Queue(
                properties.getQueue(),
                true,
                false,
                false,
                Map.of(
                        "x-dead-letter-exchange", properties.getDeadLetterExchange(),
                        "x-dead-letter-routing-key", properties.getDeadLetterRoutingKey()
                )
        );
    }

    @Bean
    public Binding auctionHotBidBinding(Queue auctionHotBidQueue,
                                        DirectExchange auctionHotBidExchange,
                                        AuctionAsyncPersistenceProperties properties) {
        return BindingBuilder.bind(auctionHotBidQueue)
                .to(auctionHotBidExchange)
                .with(properties.getRoutingKey());
    }

    @Bean
    public DirectExchange auctionHotBidDeadLetterExchange(AuctionAsyncPersistenceProperties properties) {
        return new DirectExchange(properties.getDeadLetterExchange(), true, false);
    }

    @Bean
    public Queue auctionHotBidDeadLetterQueue(AuctionAsyncPersistenceProperties properties) {
        return new Queue(properties.getDeadLetterQueue(), true);
    }

    @Bean
    public Binding auctionHotBidDeadLetterBinding(Queue auctionHotBidDeadLetterQueue,
                                                  DirectExchange auctionHotBidDeadLetterExchange,
                                                  AuctionAsyncPersistenceProperties properties) {
        return BindingBuilder.bind(auctionHotBidDeadLetterQueue)
                .to(auctionHotBidDeadLetterExchange)
                .with(properties.getDeadLetterRoutingKey());
    }

    @Bean
    public SimpleRabbitListenerContainerFactory hotBidRabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            RabbitTemplate rabbitTemplate,
            AuctionAsyncPersistenceProperties properties
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(hotBidRetryAdvice(rabbitTemplate, properties));
        return factory;
    }

    private Advice hotBidRetryAdvice(RabbitTemplate rabbitTemplate,
                                     AuctionAsyncPersistenceProperties properties) {
        return RetryInterceptorBuilder.stateless()
                .maxRetries(Math.max(0, properties.getMaxAttempts() - 1))
                .backOffOptions(
                        properties.getInitialInterval().toMillis(),
                        properties.getMultiplier(),
                        properties.getMaxInterval().toMillis()
                )
                .recoverer(new RepublishMessageRecoverer(
                        rabbitTemplate,
                        properties.getDeadLetterExchange(),
                        properties.getDeadLetterRoutingKey()
                ))
                .build();
    }
}

package com.auction.backend.auction.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "auction.persistence.rabbitmq.enabled", havingValue = "true")
public class RabbitAuctionPersistenceConfig {

    @Bean
    public DirectExchange auctionHotBidExchange(AuctionAsyncPersistenceProperties properties) {
        return new DirectExchange(properties.getExchange(), true, false);
    }

    @Bean
    public Queue auctionHotBidQueue(AuctionAsyncPersistenceProperties properties) {
        return new Queue(properties.getQueue(), true);
    }

    @Bean
    public Binding auctionHotBidBinding(Queue auctionHotBidQueue,
                                        DirectExchange auctionHotBidExchange,
                                        AuctionAsyncPersistenceProperties properties) {
        return BindingBuilder.bind(auctionHotBidQueue)
                .to(auctionHotBidExchange)
                .with(properties.getRoutingKey());
    }
}

package com.auction.backend.auction.service;

import com.auction.backend.auction.config.AuctionAsyncPersistenceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name = "auction.persistence.rabbitmq.enabled", havingValue = "true")
public class RabbitHotBidPersistenceGateway implements HotBidPersistenceGateway {

    private static final Logger log = LoggerFactory.getLogger(RabbitHotBidPersistenceGateway.class);

    private final RabbitTemplate rabbitTemplate;
    private final AuctionAsyncPersistenceProperties properties;
    private final JsonMapper jsonMapper;
    private final HotBidPersistenceStore hotBidPersistenceStore;
    private final HotBidPersistenceLogService hotBidPersistenceLogService;

    public RabbitHotBidPersistenceGateway(RabbitTemplate rabbitTemplate,
                                          AuctionAsyncPersistenceProperties properties,
                                          JsonMapper jsonMapper,
                                          HotBidPersistenceStore hotBidPersistenceStore,
                                          HotBidPersistenceLogService hotBidPersistenceLogService) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.hotBidPersistenceStore = hotBidPersistenceStore;
        this.hotBidPersistenceLogService = hotBidPersistenceLogService;
    }

    @Override
    public void persist(HotBidPersistenceMessage message) {
        hotBidPersistenceLogService.recordQueued(message);
        try {
            rabbitTemplate.convertAndSend(
                    properties.getExchange(),
                    properties.getRoutingKey(),
                    jsonMapper.writeValueAsString(message)
            );
        } catch (Exception exception) {
            log.warn("Failed to publish hot bid persistence event {}, falling back to direct MySQL write", message.eventId(), exception);
            hotBidPersistenceLogService.markFallbackDirect(message, exception);
            hotBidPersistenceStore.persist(message);
            hotBidPersistenceLogService.markSuccess(message);
        }
    }
}

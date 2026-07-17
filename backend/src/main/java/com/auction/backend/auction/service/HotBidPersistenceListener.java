package com.auction.backend.auction.service;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name = "auction.persistence.rabbitmq.enabled", havingValue = "true")
public class HotBidPersistenceListener {

    private final JsonMapper jsonMapper;
    private final HotBidPersistenceStore hotBidPersistenceStore;

    public HotBidPersistenceListener(JsonMapper jsonMapper,
                                     HotBidPersistenceStore hotBidPersistenceStore) {
        this.jsonMapper = jsonMapper;
        this.hotBidPersistenceStore = hotBidPersistenceStore;
    }

    @RabbitListener(queues = "${auction.persistence.rabbitmq.queue:auction.hot-bid.persist.queue}")
    public void onMessage(String payload) throws Exception {
        HotBidPersistenceMessage message = jsonMapper.readValue(payload, HotBidPersistenceMessage.class);
        hotBidPersistenceStore.persist(message);
    }
}

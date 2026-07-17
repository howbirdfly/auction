package com.auction.backend.auction.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "auction.persistence.rabbitmq.enabled", havingValue = "false", matchIfMissing = true)
public class DirectHotBidPersistenceGateway implements HotBidPersistenceGateway {

    private final HotBidPersistenceStore hotBidPersistenceStore;

    public DirectHotBidPersistenceGateway(HotBidPersistenceStore hotBidPersistenceStore) {
        this.hotBidPersistenceStore = hotBidPersistenceStore;
    }

    @Override
    public void persist(HotBidPersistenceMessage message) {
        hotBidPersistenceStore.persist(message);
    }
}

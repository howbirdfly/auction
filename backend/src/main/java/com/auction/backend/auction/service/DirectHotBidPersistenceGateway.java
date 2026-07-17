package com.auction.backend.auction.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "auction.persistence.rabbitmq.enabled", havingValue = "false", matchIfMissing = true)
public class DirectHotBidPersistenceGateway implements HotBidPersistenceGateway {

    private final HotBidPersistenceStore hotBidPersistenceStore;
    private final HotBidPersistenceLogService hotBidPersistenceLogService;

    public DirectHotBidPersistenceGateway(HotBidPersistenceStore hotBidPersistenceStore,
                                          HotBidPersistenceLogService hotBidPersistenceLogService) {
        this.hotBidPersistenceStore = hotBidPersistenceStore;
        this.hotBidPersistenceLogService = hotBidPersistenceLogService;
    }

    @Override
    public void persist(HotBidPersistenceMessage message) {
        hotBidPersistenceLogService.markProcessing(message);
        try {
            hotBidPersistenceStore.persist(message);
            hotBidPersistenceLogService.markSuccess(message);
        } catch (RuntimeException exception) {
            hotBidPersistenceLogService.markFailed(message, exception);
            throw exception;
        }
    }
}

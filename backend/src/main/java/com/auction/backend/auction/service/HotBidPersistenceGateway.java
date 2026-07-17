package com.auction.backend.auction.service;

public interface HotBidPersistenceGateway {

    void persist(HotBidPersistenceMessage message);
}

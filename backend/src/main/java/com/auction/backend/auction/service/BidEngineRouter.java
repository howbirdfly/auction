package com.auction.backend.auction.service;

import com.auction.backend.auction.dto.AuctionRoomSnapshot;
import com.auction.backend.auction.dto.BidRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BidEngineRouter implements BidEngine {

    private final BidEngine mysqlBidEngine;
    private final ObjectProvider<RedisBidEngine> redisBidEngineProvider;
    private final HotRoomManager hotRoomManager;

    public BidEngineRouter(MysqlBidEngine mysqlBidEngine,
                           ObjectProvider<RedisBidEngine> redisBidEngineProvider,
                           HotRoomManager hotRoomManager) {
        this.mysqlBidEngine = mysqlBidEngine;
        this.redisBidEngineProvider = redisBidEngineProvider;
        this.hotRoomManager = hotRoomManager;
    }

    @Override
    public AuctionRoomSnapshot placeBid(String roomId, BidRequest request) {
        HotRoomStatus status = hotRoomManager.status(roomId);
        if (status == null || status == HotRoomStatus.REDIS_UNAVAILABLE) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "hot auction is temporarily unavailable while Redis recovers"
            );
        }

        if (status == HotRoomStatus.HOT) {
            RedisBidEngine redisBidEngine = redisBidEngineProvider.getIfAvailable();
            if (redisBidEngine == null) {
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "hot auction engine is unavailable"
                );
            }
            return redisBidEngine.placeBid(roomId, request);
        }

        return mysqlBidEngine.placeBid(roomId, request);
    }
}

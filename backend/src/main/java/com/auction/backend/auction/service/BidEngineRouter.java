package com.auction.backend.auction.service;

import com.auction.backend.auction.dto.AuctionRoomSnapshot;
import com.auction.backend.auction.dto.BidRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

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
        if (hotRoomManager.isHot(roomId)) {
            RedisBidEngine redisBidEngine = redisBidEngineProvider.getIfAvailable();
            if (redisBidEngine != null) {
                return redisBidEngine.placeBid(roomId, request);
            }
        }

        return mysqlBidEngine.placeBid(roomId, request);
    }
}

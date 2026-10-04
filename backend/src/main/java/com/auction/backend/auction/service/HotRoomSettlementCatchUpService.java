package com.auction.backend.auction.service;

import com.auction.backend.auction.cache.AuctionCacheService;
import com.auction.backend.auction.dto.AuctionRoomSnapshot;
import com.auction.backend.auction.model.AuctionRoom;
import com.auction.backend.auction.model.AuctionStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class HotRoomSettlementCatchUpService {

    private final AuctionCacheService auctionCacheService;
    private final HotRoomManager hotRoomManager;
    private final HotBidPersistenceCompensationService hotBidPersistenceCompensationService;
    private final ObjectProvider<RedisHotBidStreamPersistenceService> redisHotBidStreamPersistenceServiceProvider;

    public HotRoomSettlementCatchUpService(AuctionCacheService auctionCacheService,
                                           HotRoomManager hotRoomManager,
                                           HotBidPersistenceCompensationService hotBidPersistenceCompensationService,
                                           ObjectProvider<RedisHotBidStreamPersistenceService> redisHotBidStreamPersistenceServiceProvider) {
        this.auctionCacheService = auctionCacheService;
        this.hotRoomManager = hotRoomManager;
        this.hotBidPersistenceCompensationService = hotBidPersistenceCompensationService;
        this.redisHotBidStreamPersistenceServiceProvider = redisHotBidStreamPersistenceServiceProvider;
    }

    public boolean isReadyForSettlement(AuctionRoom room) {
        HotRoomStatus status = hotRoomManager.status(room.getRoomId());
        if (status == HotRoomStatus.COLD) {
            return true;
        }
        if (status == HotRoomStatus.REDIS_UNAVAILABLE) {
            return false;
        }

        Optional<AuctionRoomSnapshot> hotSnapshot = auctionCacheService.getRoom(room.getRoomId());
        if (hotSnapshot.isEmpty()) {
            return false;
        }
        long targetVersion = targetBidVersion(hotSnapshot.get());

        RedisHotBidStreamPersistenceService streamPersistenceService =
                redisHotBidStreamPersistenceServiceProvider.getIfAvailable();
        if (streamPersistenceService != null) {
            return streamPersistenceService.catchUpRoom(room.getRoomId(), targetVersion);
        }
        return hotBidPersistenceCompensationService.catchUpRoom(room.getRoomId(), targetVersion);
    }

    private long targetBidVersion(AuctionRoomSnapshot snapshot) {
        if (snapshot.status() == AuctionStatus.CLOSED) {
            return Math.max(0L, snapshot.version() - 1L);
        }
        return snapshot.version();
    }

}

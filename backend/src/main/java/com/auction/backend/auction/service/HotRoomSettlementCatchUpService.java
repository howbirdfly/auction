package com.auction.backend.auction.service;

import com.auction.backend.auction.cache.AuctionCacheService;
import com.auction.backend.auction.dto.AuctionRoomSnapshot;
import com.auction.backend.auction.model.AuctionRoom;
import com.auction.backend.auction.model.AuctionStatus;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class HotRoomSettlementCatchUpService {

    private final AuctionCacheService auctionCacheService;
    private final HotRoomManager hotRoomManager;
    private final HotBidPersistenceCompensationService hotBidPersistenceCompensationService;

    public HotRoomSettlementCatchUpService(AuctionCacheService auctionCacheService,
                                           HotRoomManager hotRoomManager,
                                           HotBidPersistenceCompensationService hotBidPersistenceCompensationService) {
        this.auctionCacheService = auctionCacheService;
        this.hotRoomManager = hotRoomManager;
        this.hotBidPersistenceCompensationService = hotBidPersistenceCompensationService;
    }

    public boolean isReadyForSettlement(AuctionRoom room) {
        if (!hotRoomManager.isHot(room.getRoomId())) {
            return true;
        }

        Optional<AuctionRoomSnapshot> hotSnapshot = auctionCacheService.getRoom(room.getRoomId());
        long targetVersion = hotSnapshot
                .map(this::targetBidVersion)
                .orElseGet(() -> targetBidVersion(room));

        return hotBidPersistenceCompensationService.catchUpRoom(room.getRoomId(), targetVersion);
    }

    private long targetBidVersion(AuctionRoomSnapshot snapshot) {
        if (snapshot.status() == AuctionStatus.CLOSED) {
            return Math.max(0L, snapshot.version() - 1L);
        }
        return snapshot.version();
    }

    private long targetBidVersion(AuctionRoom room) {
        if (room.getStatus() == AuctionStatus.CLOSED) {
            return Math.max(0L, room.getVersion() - 1L);
        }
        return room.getVersion();
    }
}

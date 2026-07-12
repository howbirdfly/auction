package com.auction.backend.auction.service;

import com.auction.backend.auction.dto.AuctionLeaderboardEntry;
import com.auction.backend.auction.dto.AuctionRoomSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@ConditionalOnProperty(name = "auction.cache.redis.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpHotRoomManager implements HotRoomManager {

    @Override
    public boolean recordAccess(String roomId) {
        return false;
    }

    @Override
    public boolean isHot(String roomId) {
        return false;
    }

    @Override
    public void markHot(AuctionRoomSnapshot snapshot, List<AuctionLeaderboardEntry> leaderboard) {
        // Redis-based hot room routing is disabled for the current environment.
    }

    @Override
    public void clear(String roomId) {
        // Redis-based hot room routing is disabled for the current environment.
    }
}

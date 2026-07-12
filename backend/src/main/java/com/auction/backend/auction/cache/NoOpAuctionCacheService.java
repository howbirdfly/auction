package com.auction.backend.auction.cache;

import com.auction.backend.auction.dto.AuctionLeaderboardEntry;
import com.auction.backend.auction.dto.AuctionRoomSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
@ConditionalOnProperty(name = "auction.cache.redis.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpAuctionCacheService implements AuctionCacheService {

    @Override
    public Optional<List<AuctionRoomSnapshot>> getLobby() {
        return Optional.empty();
    }

    @Override
    public Optional<AuctionRoomSnapshot> getRoom(String roomId) {
        return Optional.empty();
    }

    @Override
    public Optional<List<AuctionLeaderboardEntry>> getLeaderboard(String roomId) {
        return Optional.empty();
    }

    @Override
    public void cacheLobby(List<AuctionRoomSnapshot> rooms) {
        // Redis caching is disabled for the current environment.
    }

    @Override
    public void cacheRoom(AuctionRoomSnapshot room) {
        // Redis caching is disabled for the current environment.
    }

    @Override
    public void cacheLeaderboard(String roomId, List<AuctionLeaderboardEntry> entries, AuctionRoomSnapshot room) {
        // Redis caching is disabled for the current environment.
    }

    @Override
    public void recordBid(String roomId, String userId, String nickname, BigDecimal amount, AuctionRoomSnapshot room) {
        // Redis caching is disabled for the current environment.
    }

    @Override
    public void evictLobby() {
        // Redis caching is disabled for the current environment.
    }

    @Override
    public void evictRoom(String roomId) {
        // Redis caching is disabled for the current environment.
    }

    @Override
    public void evictLeaderboard(String roomId) {
        // Redis caching is disabled for the current environment.
    }
}

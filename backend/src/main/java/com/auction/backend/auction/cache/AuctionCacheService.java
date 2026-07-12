package com.auction.backend.auction.cache;

import com.auction.backend.auction.dto.AuctionLeaderboardEntry;
import com.auction.backend.auction.dto.AuctionRoomSnapshot;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface AuctionCacheService {

    Optional<List<AuctionRoomSnapshot>> getLobby();

    Optional<AuctionRoomSnapshot> getRoom(String roomId);

    Optional<List<AuctionLeaderboardEntry>> getLeaderboard(String roomId);

    void cacheLobby(List<AuctionRoomSnapshot> rooms);

    void cacheRoom(AuctionRoomSnapshot room);

    void cacheLeaderboard(String roomId, List<AuctionLeaderboardEntry> entries, AuctionRoomSnapshot room);

    void recordBid(String roomId, String userId, String nickname, BigDecimal amount, AuctionRoomSnapshot room);

    void evictLobby();

    void evictRoom(String roomId);

    void evictLeaderboard(String roomId);
}

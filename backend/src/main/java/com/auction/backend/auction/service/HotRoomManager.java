package com.auction.backend.auction.service;

import com.auction.backend.auction.dto.AuctionLeaderboardEntry;
import com.auction.backend.auction.dto.AuctionRoomSnapshot;

import java.util.List;

public interface HotRoomManager {

    boolean recordAccess(String roomId);

    boolean recordBid(String roomId);

    boolean isHot(String roomId);

    HotRoomStatus status(String roomId);

    boolean shouldStayHot(String roomId);

    void markHot(AuctionRoomSnapshot snapshot, List<AuctionLeaderboardEntry> leaderboard);

    void cacheQualification(String roomId, String userId);

    void clear(String roomId);
}

package com.auction.backend.auction.dto;

import java.util.List;

public record AuctionLeaderboardSnapshot(
        String roomId,
        long version,
        List<AuctionLeaderboardEntry> leaderboard
) {
}

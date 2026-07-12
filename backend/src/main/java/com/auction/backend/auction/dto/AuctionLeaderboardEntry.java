package com.auction.backend.auction.dto;

import java.math.BigDecimal;

public record AuctionLeaderboardEntry(
        int rank,
        String userId,
        String nickname,
        BigDecimal amount
) {
}

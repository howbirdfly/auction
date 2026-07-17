package com.auction.backend.auction.dto;

import com.auction.backend.auction.model.AuctionStatus;
import com.auction.backend.auction.model.BidRecord;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AuctionRoomSnapshot(
        String roomId,
        String itemTitle,
        String anchorName,
        String imageUrl,
        AuctionStatus status,
        BigDecimal startPrice,
        BigDecimal currentPrice,
        BigDecimal stepPrice,
        BigDecimal minNextBid,
        String leaderNickname,
        boolean registrationRequired,
        BigDecimal depositAmount,
        Instant endsAt,
        long secondsRemaining,
        int bidCount,
        List<BidRecord> recentBids
) {
}

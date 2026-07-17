package com.auction.backend.auction.service;

import com.auction.backend.auction.model.AuctionStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record HotBidPersistenceMessage(
        String eventId,
        String roomId,
        String userId,
        String nickname,
        BigDecimal amount,
        long roomVersion,
        Instant bidTime,
        Instant endsAt,
        AuctionStatus roomStatus
) {
}

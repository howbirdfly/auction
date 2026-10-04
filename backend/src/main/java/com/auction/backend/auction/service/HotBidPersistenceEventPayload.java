package com.auction.backend.auction.service;

import com.auction.backend.auction.model.AuctionStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record HotBidPersistenceEventPayload(
        String eventId,
        String requestId,
        String roomId,
        String userId,
        String nickname,
        String amount,
        String previousLeaderUserId,
        String previousAmount,
        long roomVersion,
        long bidTimeEpochMilli,
        long endsAtEpochMilli,
        String roomStatus
) {

    public HotBidPersistenceMessage toMessage() {
        return new HotBidPersistenceMessage(
                eventId,
                requestId,
                roomId,
                userId,
                nickname,
                new BigDecimal(amount),
                previousLeaderUserId == null || previousLeaderUserId.isBlank() ? null : previousLeaderUserId,
                new BigDecimal(previousAmount),
                roomVersion,
                Instant.ofEpochMilli(bidTimeEpochMilli),
                Instant.ofEpochMilli(endsAtEpochMilli),
                AuctionStatus.valueOf(roomStatus)
        );
    }
}

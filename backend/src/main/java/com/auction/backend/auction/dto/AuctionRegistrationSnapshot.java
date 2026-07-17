package com.auction.backend.auction.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record AuctionRegistrationSnapshot(
        String roomId,
        String userId,
        String nickname,
        BigDecimal depositAmount,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
}

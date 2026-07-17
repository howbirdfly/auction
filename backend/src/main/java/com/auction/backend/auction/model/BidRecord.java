package com.auction.backend.auction.model;

import java.math.BigDecimal;
import java.time.Instant;

public record BidRecord(
        String userId,
        String nickname,
        BigDecimal amount,
        long version,
        Instant bidTime
) {
}

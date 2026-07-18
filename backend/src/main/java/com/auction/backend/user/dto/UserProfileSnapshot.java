package com.auction.backend.user.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record UserProfileSnapshot(
        String userId,
        String account,
        String nickname,
        String avatarUrl,
        String bio,
        BigDecimal balance,
        BigDecimal frozenAmount,
        Instant createdAt,
        Instant updatedAt
) {
}

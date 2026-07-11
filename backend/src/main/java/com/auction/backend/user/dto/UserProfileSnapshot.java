package com.auction.backend.user.dto;

import java.time.Instant;

public record UserProfileSnapshot(
        String userId,
        String account,
        String nickname,
        String avatarUrl,
        String bio,
        Instant createdAt,
        Instant updatedAt
) {
}

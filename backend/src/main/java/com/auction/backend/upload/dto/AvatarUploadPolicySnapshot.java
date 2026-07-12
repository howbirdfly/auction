package com.auction.backend.upload.dto;

import java.time.Instant;

public record AvatarUploadPolicySnapshot(
        String host,
        String objectKey,
        String policy,
        String accessKeyId,
        String signature,
        int successActionStatus,
        String publicUrl,
        Instant expireAt
) {
}

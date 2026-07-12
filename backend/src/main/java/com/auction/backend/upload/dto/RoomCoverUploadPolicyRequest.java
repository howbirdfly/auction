package com.auction.backend.upload.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RoomCoverUploadPolicyRequest(
        @NotBlank(message = "cannot be blank")
        String userId,
        @NotBlank(message = "cannot be blank")
        @Size(max = 255, message = "must be at most 255 characters")
        String fileName,
        @NotBlank(message = "cannot be blank")
        @Size(max = 128, message = "must be at most 128 characters")
        String contentType
) {
}

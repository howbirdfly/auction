package com.auction.backend.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank(message = "cannot be blank")
        @Size(max = 32, message = "must be at most 32 characters")
        String account,
        @NotBlank(message = "cannot be blank")
        @Size(min = 6, max = 64, message = "must be between 6 and 64 characters")
        String password,
        @NotBlank(message = "cannot be blank")
        @Size(max = 32, message = "must be at most 32 characters")
        String nickname,
        @Size(max = 512, message = "must be at most 512 characters")
        String avatarUrl,
        @Size(max = 255, message = "must be at most 255 characters")
        String bio
) {
}

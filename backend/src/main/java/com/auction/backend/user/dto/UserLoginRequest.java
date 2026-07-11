package com.auction.backend.user.dto;

import jakarta.validation.constraints.NotBlank;

public record UserLoginRequest(
        @NotBlank(message = "cannot be blank")
        String account,
        @NotBlank(message = "cannot be blank")
        String password
) {
}

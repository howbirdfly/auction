package com.auction.backend.auction.dto;

import jakarta.validation.constraints.NotBlank;

public record AuctionRegistrationRequest(
        @NotBlank(message = "cannot be blank")
        String userId,
        @NotBlank(message = "cannot be blank")
        String nickname
) {
}

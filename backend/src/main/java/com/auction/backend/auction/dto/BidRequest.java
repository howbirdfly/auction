package com.auction.backend.auction.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record BidRequest(
        @NotBlank(message = "cannot be blank")
        String requestId,
        @NotBlank(message = "cannot be blank")
        String userId,
        @NotBlank(message = "cannot be blank")
        String nickname,
        @DecimalMin(value = "0.01", message = "must be greater than 0")
        BigDecimal amount
) {
}

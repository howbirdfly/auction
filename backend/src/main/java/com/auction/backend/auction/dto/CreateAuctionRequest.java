package com.auction.backend.auction.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record CreateAuctionRequest(
        @NotBlank(message = "cannot be blank")
        String itemTitle,
        @NotBlank(message = "cannot be blank")
        String anchorName,
        String imageUrl,
        @DecimalMin(value = "0.01", message = "must be greater than 0")
        BigDecimal startPrice,
        @DecimalMin(value = "0.01", message = "must be greater than 0")
        BigDecimal stepPrice,
        Boolean registrationRequired,
        @DecimalMin(value = "0.00", message = "must be greater than or equal to 0")
        BigDecimal depositAmount,
        @Min(value = 30, message = "must be at least 30 seconds")
        long durationSeconds
) {
}

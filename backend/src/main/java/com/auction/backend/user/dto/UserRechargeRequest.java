package com.auction.backend.user.dto;

import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;

public record UserRechargeRequest(
        @DecimalMin(value = "0.01", message = "must be greater than 0")
        BigDecimal amount
) {
}

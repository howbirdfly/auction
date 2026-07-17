package com.auction.backend.auction.dto;

import java.math.BigDecimal;

public record AuctionQualificationSnapshot(
        boolean registrationRequired,
        BigDecimal depositAmount,
        boolean registered,
        boolean canBid,
        String status,
        String message
) {
}

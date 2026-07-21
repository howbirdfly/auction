package com.auction.backend.user.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record WalletTransactionSnapshot(
        Long id,
        String transactionType,
        BigDecimal availableDelta,
        BigDecimal frozenDelta,
        BigDecimal balanceAfter,
        BigDecimal frozenAfter,
        String referenceType,
        String referenceId,
        String description,
        Instant createdAt
) {
}

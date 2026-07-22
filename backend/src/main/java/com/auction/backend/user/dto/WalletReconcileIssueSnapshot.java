package com.auction.backend.user.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record WalletReconcileIssueSnapshot(
        Long id,
        String userId,
        String account,
        BigDecimal balance,
        BigDecimal frozenAmount,
        BigDecimal latestBalanceAfter,
        BigDecimal latestFrozenAfter,
        BigDecimal balanceDiff,
        BigDecimal frozenDiff,
        int transactionCount,
        String status,
        Instant createdAt,
        Instant resolvedAt
) {
}

package com.auction.backend.user.dto;

import java.math.BigDecimal;

public record WalletReconcileResult(
        String userId,
        String account,
        BigDecimal balance,
        BigDecimal frozenAmount,
        BigDecimal latestBalanceAfter,
        BigDecimal latestFrozenAfter,
        BigDecimal availableDeltaTotal,
        BigDecimal frozenDeltaTotal,
        BigDecimal balanceDiff,
        BigDecimal frozenDiff,
        int transactionCount,
        boolean matched
) {
}

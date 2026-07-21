package com.auction.backend.user.service;

import com.auction.backend.user.dto.WalletTransactionSnapshot;
import com.auction.backend.user.mapper.WalletTransactionMapper;
import com.auction.backend.user.model.UserAccount;
import com.auction.backend.user.model.WalletTransaction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Service
public class WalletTransactionService {

    private static final int DEFAULT_LIMIT = 50;

    private final WalletTransactionMapper walletTransactionMapper;

    public WalletTransactionService(WalletTransactionMapper walletTransactionMapper) {
        this.walletTransactionMapper = walletTransactionMapper;
    }

    @Transactional(readOnly = true)
    public List<WalletTransactionSnapshot> listByUserId(String userId) {
        return walletTransactionMapper.findLatestByUserId(userId, DEFAULT_LIMIT).stream()
                .map(transaction -> new WalletTransactionSnapshot(
                        transaction.getId(),
                        transaction.getTransactionType(),
                        transaction.getAvailableDelta(),
                        transaction.getFrozenDelta(),
                        transaction.getBalanceAfter(),
                        transaction.getFrozenAfter(),
                        transaction.getReferenceType(),
                        transaction.getReferenceId(),
                        transaction.getDescription(),
                        transaction.getCreatedAt()
                ))
                .toList();
    }

    @Transactional
    public void record(UserAccount userAccount,
                       String transactionType,
                       BigDecimal availableDelta,
                       BigDecimal frozenDelta,
                       String referenceType,
                       String referenceId,
                       String description,
                       String businessKey) {
        if (businessKey != null && !businessKey.isBlank()
                && walletTransactionMapper.findByBusinessKey(businessKey) != null) {
            return;
        }

        WalletTransaction transaction = new WalletTransaction();
        transaction.setBusinessKey((businessKey == null || businessKey.isBlank()) ? null : businessKey);
        transaction.setUserId(userAccount.getUserId());
        transaction.setAccount(userAccount.getAccount());
        transaction.setTransactionType(transactionType);
        transaction.setAvailableDelta(safeAmount(availableDelta));
        transaction.setFrozenDelta(safeAmount(frozenDelta));
        transaction.setBalanceAfter(safeAmount(userAccount.getBalance()));
        transaction.setFrozenAfter(safeAmount(userAccount.getFrozenAmount()));
        transaction.setReferenceType(referenceType);
        transaction.setReferenceId(referenceId);
        transaction.setDescription(description);
        transaction.setCreatedAt(Instant.now());
        walletTransactionMapper.insert(transaction);
    }

    private BigDecimal safeAmount(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }
}

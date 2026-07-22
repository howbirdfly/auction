package com.auction.backend.user.service;

import com.auction.backend.user.dto.WalletReconcileIssueSnapshot;
import com.auction.backend.user.dto.WalletReconcileResult;
import com.auction.backend.user.mapper.UserAccountMapper;
import com.auction.backend.user.mapper.WalletReconcileIssueMapper;
import com.auction.backend.user.mapper.WalletTransactionMapper;
import com.auction.backend.user.model.UserAccount;
import com.auction.backend.user.model.WalletReconcileIssue;
import com.auction.backend.user.model.WalletTransaction;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

@Service
public class WalletReconcileService {

    private final UserAccountMapper userAccountMapper;
    private final WalletTransactionMapper walletTransactionMapper;
    private final WalletReconcileIssueMapper walletReconcileIssueMapper;

    public WalletReconcileService(UserAccountMapper userAccountMapper,
                                  WalletTransactionMapper walletTransactionMapper,
                                  WalletReconcileIssueMapper walletReconcileIssueMapper) {
        this.userAccountMapper = userAccountMapper;
        this.walletTransactionMapper = walletTransactionMapper;
        this.walletReconcileIssueMapper = walletReconcileIssueMapper;
    }

    @Transactional
    public List<WalletReconcileResult> reconcileAll() {
        return userAccountMapper.findAllOrderByCreatedAtDesc().stream()
                .map(userAccount -> {
                    WalletReconcileResult result = reconcile(userAccount);
                    syncIssueState(result);
                    return result;
                })
                .toList();
    }

    @Transactional
    public WalletReconcileResult reconcileUser(String userId) {
        UserAccount userAccount = userAccountMapper.findById(userId);
        if (userAccount == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found");
        }
        WalletReconcileResult result = reconcile(userAccount);
        syncIssueState(result);
        return result;
    }

    @Transactional(readOnly = true)
    public List<WalletReconcileIssueSnapshot> listIssues(int limit) {
        return walletReconcileIssueMapper.findLatest(resolveLimit(limit)).stream()
                .map(this::toIssueSnapshot)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WalletReconcileIssueSnapshot> listUserIssues(String userId, int limit) {
        return walletReconcileIssueMapper.findByUserId(userId, resolveLimit(limit)).stream()
                .map(this::toIssueSnapshot)
                .toList();
    }

    private WalletReconcileResult reconcile(UserAccount userAccount) {
        List<WalletTransaction> transactions = walletTransactionMapper.findAllByUserId(userAccount.getUserId());
        WalletTransaction latest = transactions.stream()
                .max(Comparator.comparing(WalletTransaction::getCreatedAt).thenComparing(WalletTransaction::getId))
                .orElse(null);

        BigDecimal balance = safeAmount(userAccount.getBalance());
        BigDecimal frozenAmount = safeAmount(userAccount.getFrozenAmount());
        BigDecimal latestBalanceAfter = latest == null ? BigDecimal.ZERO : safeAmount(latest.getBalanceAfter());
        BigDecimal latestFrozenAfter = latest == null ? BigDecimal.ZERO : safeAmount(latest.getFrozenAfter());
        BigDecimal availableDeltaTotal = transactions.stream()
                .map(WalletTransaction::getAvailableDelta)
                .map(this::safeAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal frozenDeltaTotal = transactions.stream()
                .map(WalletTransaction::getFrozenDelta)
                .map(this::safeAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal balanceDiff = balance.subtract(latestBalanceAfter);
        BigDecimal frozenDiff = frozenAmount.subtract(latestFrozenAfter);

        return new WalletReconcileResult(
                userAccount.getUserId(),
                userAccount.getAccount(),
                balance,
                frozenAmount,
                latestBalanceAfter,
                latestFrozenAfter,
                availableDeltaTotal,
                frozenDeltaTotal,
                balanceDiff,
                frozenDiff,
                transactions.size(),
                isZero(balanceDiff) && isZero(frozenDiff)
        );
    }

    private void syncIssueState(WalletReconcileResult result) {
        if (result.matched()) {
            walletReconcileIssueMapper.markOpenResolvedByUserId(result.userId(), Instant.now());
            return;
        }

        WalletReconcileIssue openIssue = walletReconcileIssueMapper.findOpenByUserId(result.userId());
        WalletReconcileIssue issue = toIssue(result, openIssue == null ? null : openIssue.getId());
        if (openIssue == null) {
            walletReconcileIssueMapper.insert(issue);
        } else {
            walletReconcileIssueMapper.updateOpen(issue);
        }
    }

    private WalletReconcileIssue toIssue(WalletReconcileResult result, Long id) {
        WalletReconcileIssue issue = new WalletReconcileIssue();
        issue.setId(id);
        issue.setUserId(result.userId());
        issue.setAccount(result.account());
        issue.setBalance(result.balance());
        issue.setFrozenAmount(result.frozenAmount());
        issue.setLatestBalanceAfter(result.latestBalanceAfter());
        issue.setLatestFrozenAfter(result.latestFrozenAfter());
        issue.setBalanceDiff(result.balanceDiff());
        issue.setFrozenDiff(result.frozenDiff());
        issue.setTransactionCount(result.transactionCount());
        issue.setStatus("OPEN");
        issue.setCreatedAt(Instant.now());
        return issue;
    }

    private WalletReconcileIssueSnapshot toIssueSnapshot(WalletReconcileIssue issue) {
        return new WalletReconcileIssueSnapshot(
                issue.getId(),
                issue.getUserId(),
                issue.getAccount(),
                issue.getBalance(),
                issue.getFrozenAmount(),
                issue.getLatestBalanceAfter(),
                issue.getLatestFrozenAfter(),
                issue.getBalanceDiff(),
                issue.getFrozenDiff(),
                issue.getTransactionCount(),
                issue.getStatus(),
                issue.getCreatedAt(),
                issue.getResolvedAt()
        );
    }

    private int resolveLimit(int limit) {
        if (limit <= 0) {
            return 50;
        }
        return Math.min(limit, 200);
    }

    private boolean isZero(BigDecimal amount) {
        return amount.compareTo(BigDecimal.ZERO) == 0;
    }

    private BigDecimal safeAmount(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }
}

package com.auction.backend.user.model;

import java.math.BigDecimal;
import java.time.Instant;

public class WalletReconcileIssue {

    private Long id;
    private String userId;
    private String account;
    private BigDecimal balance;
    private BigDecimal frozenAmount;
    private BigDecimal latestBalanceAfter;
    private BigDecimal latestFrozenAfter;
    private BigDecimal balanceDiff;
    private BigDecimal frozenDiff;
    private int transactionCount;
    private String status;
    private Instant createdAt;
    private Instant resolvedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public BigDecimal getFrozenAmount() {
        return frozenAmount;
    }

    public void setFrozenAmount(BigDecimal frozenAmount) {
        this.frozenAmount = frozenAmount;
    }

    public BigDecimal getLatestBalanceAfter() {
        return latestBalanceAfter;
    }

    public void setLatestBalanceAfter(BigDecimal latestBalanceAfter) {
        this.latestBalanceAfter = latestBalanceAfter;
    }

    public BigDecimal getLatestFrozenAfter() {
        return latestFrozenAfter;
    }

    public void setLatestFrozenAfter(BigDecimal latestFrozenAfter) {
        this.latestFrozenAfter = latestFrozenAfter;
    }

    public BigDecimal getBalanceDiff() {
        return balanceDiff;
    }

    public void setBalanceDiff(BigDecimal balanceDiff) {
        this.balanceDiff = balanceDiff;
    }

    public BigDecimal getFrozenDiff() {
        return frozenDiff;
    }

    public void setFrozenDiff(BigDecimal frozenDiff) {
        this.frozenDiff = frozenDiff;
    }

    public int getTransactionCount() {
        return transactionCount;
    }

    public void setTransactionCount(int transactionCount) {
        this.transactionCount = transactionCount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }
}

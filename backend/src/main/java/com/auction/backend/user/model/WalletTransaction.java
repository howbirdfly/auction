package com.auction.backend.user.model;

import java.math.BigDecimal;
import java.time.Instant;

public class WalletTransaction {

    private Long id;
    private String businessKey;
    private String userId;
    private String account;
    private String transactionType;
    private BigDecimal availableDelta;
    private BigDecimal frozenDelta;
    private BigDecimal balanceAfter;
    private BigDecimal frozenAfter;
    private String referenceType;
    private String referenceId;
    private String description;
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBusinessKey() {
        return businessKey;
    }

    public void setBusinessKey(String businessKey) {
        this.businessKey = businessKey;
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

    public String getTransactionType() {
        return transactionType;
    }

    public void setTransactionType(String transactionType) {
        this.transactionType = transactionType;
    }

    public BigDecimal getAvailableDelta() {
        return availableDelta;
    }

    public void setAvailableDelta(BigDecimal availableDelta) {
        this.availableDelta = availableDelta;
    }

    public BigDecimal getFrozenDelta() {
        return frozenDelta;
    }

    public void setFrozenDelta(BigDecimal frozenDelta) {
        this.frozenDelta = frozenDelta;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public void setBalanceAfter(BigDecimal balanceAfter) {
        this.balanceAfter = balanceAfter;
    }

    public BigDecimal getFrozenAfter() {
        return frozenAfter;
    }

    public void setFrozenAfter(BigDecimal frozenAfter) {
        this.frozenAfter = frozenAfter;
    }

    public String getReferenceType() {
        return referenceType;
    }

    public void setReferenceType(String referenceType) {
        this.referenceType = referenceType;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public void setReferenceId(String referenceId) {
        this.referenceId = referenceId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

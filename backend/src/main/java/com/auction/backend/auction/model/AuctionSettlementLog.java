package com.auction.backend.auction.model;

import java.math.BigDecimal;
import java.time.Instant;

public class AuctionSettlementLog {

    private String roomId;
    private String winnerUserId;
    private BigDecimal finalPrice;
    private String status;
    private boolean winnerFundsSettled;
    private boolean depositsReleased;
    private int attemptCount;
    private String lastError;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant settledAt;

    public String getRoomId() {
        return roomId;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }

    public String getWinnerUserId() {
        return winnerUserId;
    }

    public void setWinnerUserId(String winnerUserId) {
        this.winnerUserId = winnerUserId;
    }

    public BigDecimal getFinalPrice() {
        return finalPrice;
    }

    public void setFinalPrice(BigDecimal finalPrice) {
        this.finalPrice = finalPrice;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isWinnerFundsSettled() {
        return winnerFundsSettled;
    }

    public void setWinnerFundsSettled(boolean winnerFundsSettled) {
        this.winnerFundsSettled = winnerFundsSettled;
    }

    public boolean isDepositsReleased() {
        return depositsReleased;
    }

    public void setDepositsReleased(boolean depositsReleased) {
        this.depositsReleased = depositsReleased;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getSettledAt() {
        return settledAt;
    }

    public void setSettledAt(Instant settledAt) {
        this.settledAt = settledAt;
    }
}

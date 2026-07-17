package com.auction.backend.auction.model;

import java.math.BigDecimal;
import java.time.Instant;

public class AuctionRoomRegistration {

    private String roomId;
    private String userId;
    private String nickname;
    private BigDecimal depositAmount;
    private AuctionRegistrationStatus status;
    private Instant createdAt;
    private Instant updatedAt;

    public AuctionRoomRegistration() {
    }

    public AuctionRoomRegistration(String roomId,
                                   String userId,
                                   String nickname,
                                   BigDecimal depositAmount,
                                   AuctionRegistrationStatus status,
                                   Instant createdAt,
                                   Instant updatedAt) {
        this.roomId = roomId;
        this.userId = userId;
        this.nickname = nickname;
        this.depositAmount = depositAmount;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getRoomId() {
        return roomId;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public BigDecimal getDepositAmount() {
        return depositAmount;
    }

    public void setDepositAmount(BigDecimal depositAmount) {
        this.depositAmount = depositAmount;
    }

    public AuctionRegistrationStatus getStatus() {
        return status;
    }

    public void setStatus(AuctionRegistrationStatus status) {
        this.status = status;
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
}

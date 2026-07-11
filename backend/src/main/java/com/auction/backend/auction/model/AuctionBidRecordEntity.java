package com.auction.backend.auction.model;

import java.math.BigDecimal;
import java.time.Instant;

public class AuctionBidRecordEntity {

    private Long id;
    private String roomId;
    private String userId;
    private String nickname;
    private BigDecimal amount;
    private Instant bidTime;

    public AuctionBidRecordEntity() {
    }

    public AuctionBidRecordEntity(String roomId,
                                  String userId,
                                  String nickname,
                                  BigDecimal amount,
                                  Instant bidTime) {
        this.roomId = roomId;
        this.userId = userId;
        this.nickname = nickname;
        this.amount = amount;
        this.bidTime = bidTime;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public Instant getBidTime() {
        return bidTime;
    }

    public void setBidTime(Instant bidTime) {
        this.bidTime = bidTime;
    }
}

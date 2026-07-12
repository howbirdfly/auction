package com.auction.backend.auction.model;

import java.math.BigDecimal;
import java.time.Instant;

public class AuctionLeaderboardRow {

    private String userId;
    private String nickname;
    private BigDecimal amount;
    private Instant latestBidTime;

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

    public Instant getLatestBidTime() {
        return latestBidTime;
    }

    public void setLatestBidTime(Instant latestBidTime) {
        this.latestBidTime = latestBidTime;
    }
}

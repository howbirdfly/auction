package com.auction.backend.auction.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedList;

public class AuctionRoom {

    private final String roomId;
    private final String itemTitle;
    private final String anchorName;
    private final String imageUrl;
    private final BigDecimal startPrice;
    private final BigDecimal stepPrice;
    private final LinkedList<BidRecord> bidRecords = new LinkedList<>();
    private BigDecimal currentPrice;
    private String leaderUserId;
    private String leaderNickname;
    private Instant endsAt;
    private AuctionStatus status;

    public AuctionRoom(String roomId,
                       String itemTitle,
                       String anchorName,
                       String imageUrl,
                       BigDecimal startPrice,
                       BigDecimal stepPrice,
                       Instant endsAt,
                       AuctionStatus status) {
        this.roomId = roomId;
        this.itemTitle = itemTitle;
        this.anchorName = anchorName;
        this.imageUrl = imageUrl;
        this.startPrice = startPrice;
        this.stepPrice = stepPrice;
        this.currentPrice = startPrice;
        this.endsAt = endsAt;
        this.status = status;
    }

    public String getRoomId() {
        return roomId;
    }

    public String getItemTitle() {
        return itemTitle;
    }

    public String getAnchorName() {
        return anchorName;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public BigDecimal getStartPrice() {
        return startPrice;
    }

    public BigDecimal getStepPrice() {
        return stepPrice;
    }

    public LinkedList<BidRecord> getBidRecords() {
        return bidRecords;
    }

    public BigDecimal getCurrentPrice() {
        return currentPrice;
    }

    public void setCurrentPrice(BigDecimal currentPrice) {
        this.currentPrice = currentPrice;
    }

    public String getLeaderUserId() {
        return leaderUserId;
    }

    public void setLeaderUserId(String leaderUserId) {
        this.leaderUserId = leaderUserId;
    }

    public String getLeaderNickname() {
        return leaderNickname;
    }

    public void setLeaderNickname(String leaderNickname) {
        this.leaderNickname = leaderNickname;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public void setEndsAt(Instant endsAt) {
        this.endsAt = endsAt;
    }

    public AuctionStatus getStatus() {
        return status;
    }

    public void setStatus(AuctionStatus status) {
        this.status = status;
    }

    public boolean hasLeader() {
        return leaderUserId != null && !leaderUserId.isBlank();
    }
}

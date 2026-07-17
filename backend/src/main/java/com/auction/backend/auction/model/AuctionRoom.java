package com.auction.backend.auction.model;

import java.math.BigDecimal;
import java.time.Instant;

public class AuctionRoom {

    private String roomId;
    private String itemTitle;
    private String anchorName;
    private String imageUrl;
    private BigDecimal startPrice;
    private BigDecimal stepPrice;
    private BigDecimal currentPrice;
    private String leaderUserId;
    private String leaderNickname;
    private boolean registrationRequired;
    private BigDecimal depositAmount;
    private Instant endsAt;
    private AuctionStatus status;
    private long version;

    public AuctionRoom() {
    }

    public AuctionRoom(String roomId,
                       String itemTitle,
                       String anchorName,
                       String imageUrl,
                       BigDecimal startPrice,
                       BigDecimal stepPrice,
                       boolean registrationRequired,
                       BigDecimal depositAmount,
                       Instant endsAt,
                       AuctionStatus status) {
        this.roomId = roomId;
        this.itemTitle = itemTitle;
        this.anchorName = anchorName;
        this.imageUrl = imageUrl;
        this.startPrice = startPrice;
        this.stepPrice = stepPrice;
        this.currentPrice = startPrice;
        this.registrationRequired = registrationRequired;
        this.depositAmount = depositAmount;
        this.endsAt = endsAt;
        this.status = status;
        this.version = 0L;
    }

    public String getRoomId() {
        return roomId;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }

    public String getItemTitle() {
        return itemTitle;
    }

    public void setItemTitle(String itemTitle) {
        this.itemTitle = itemTitle;
    }

    public String getAnchorName() {
        return anchorName;
    }

    public void setAnchorName(String anchorName) {
        this.anchorName = anchorName;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public BigDecimal getStartPrice() {
        return startPrice;
    }

    public void setStartPrice(BigDecimal startPrice) {
        this.startPrice = startPrice;
    }

    public BigDecimal getStepPrice() {
        return stepPrice;
    }

    public void setStepPrice(BigDecimal stepPrice) {
        this.stepPrice = stepPrice;
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

    public boolean isRegistrationRequired() {
        return registrationRequired;
    }

    public void setRegistrationRequired(boolean registrationRequired) {
        this.registrationRequired = registrationRequired;
    }

    public BigDecimal getDepositAmount() {
        return depositAmount;
    }

    public void setDepositAmount(BigDecimal depositAmount) {
        this.depositAmount = depositAmount;
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

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public boolean hasLeader() {
        return leaderUserId != null && !leaderUserId.isBlank();
    }
}

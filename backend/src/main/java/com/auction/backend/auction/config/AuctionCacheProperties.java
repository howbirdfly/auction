package com.auction.backend.auction.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "auction.cache.redis")
public class AuctionCacheProperties {

    private boolean enabled;
    private Duration lobbyTtl = Duration.ofMinutes(30);
    private Duration roomTtl = Duration.ofMinutes(30);
    private Duration leaderboardTtl = Duration.ofMinutes(30);
    private Duration hotRoomBuffer = Duration.ofMinutes(10);
    private int hotAccessThreshold = 30;
    private int hotBidEnterThreshold = 25;
    private int hotBidExitThreshold = 6;
    private Duration hotBidEnterWindow = Duration.ofSeconds(5);
    private Duration hotBidExitWindow = Duration.ofSeconds(60);
    private Duration bidLockTtl = Duration.ofSeconds(5);
    private Duration walletTtl = Duration.ofHours(12);
    private Duration bidRequestTtl = Duration.ofHours(2);
    private String hotBidStreamKey = "auction:hot-bid:stream";
    private String hotBidDeadLetterStreamKey = "auction:hot-bid:stream:dlq";
    private String hotBidStreamGroup = "auction-hot-bid-persist";
    private long hotBidStreamMaxLength = 100_000;
    private int hotBidStreamBatchSize = 500;
    private long hotBidStreamPollIntervalMs = 100;
    private Duration hotBidStreamPendingTimeout = Duration.ofSeconds(30);
    private long hotBidStreamMaxBacklog = 5_000;
    private String hotBidBackpressureKey = "auction:hot-bid:stream:backpressure";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getLobbyTtl() {
        return lobbyTtl;
    }

    public void setLobbyTtl(Duration lobbyTtl) {
        this.lobbyTtl = lobbyTtl;
    }

    public Duration getRoomTtl() {
        return roomTtl;
    }

    public void setRoomTtl(Duration roomTtl) {
        this.roomTtl = roomTtl;
    }

    public Duration getLeaderboardTtl() {
        return leaderboardTtl;
    }

    public void setLeaderboardTtl(Duration leaderboardTtl) {
        this.leaderboardTtl = leaderboardTtl;
    }

    public Duration getHotRoomBuffer() {
        return hotRoomBuffer;
    }

    public void setHotRoomBuffer(Duration hotRoomBuffer) {
        this.hotRoomBuffer = hotRoomBuffer;
    }

    public int getHotAccessThreshold() {
        return hotAccessThreshold;
    }

    public void setHotAccessThreshold(int hotAccessThreshold) {
        this.hotAccessThreshold = hotAccessThreshold;
    }

    public int getHotBidEnterThreshold() {
        return hotBidEnterThreshold;
    }

    public void setHotBidEnterThreshold(int hotBidEnterThreshold) {
        this.hotBidEnterThreshold = hotBidEnterThreshold;
    }

    public int getHotBidExitThreshold() {
        return hotBidExitThreshold;
    }

    public void setHotBidExitThreshold(int hotBidExitThreshold) {
        this.hotBidExitThreshold = hotBidExitThreshold;
    }

    public Duration getHotBidEnterWindow() {
        return hotBidEnterWindow;
    }

    public void setHotBidEnterWindow(Duration hotBidEnterWindow) {
        this.hotBidEnterWindow = hotBidEnterWindow;
    }

    public Duration getHotBidExitWindow() {
        return hotBidExitWindow;
    }

    public void setHotBidExitWindow(Duration hotBidExitWindow) {
        this.hotBidExitWindow = hotBidExitWindow;
    }

    public Duration getBidLockTtl() {
        return bidLockTtl;
    }

    public void setBidLockTtl(Duration bidLockTtl) {
        this.bidLockTtl = bidLockTtl;
    }

    public Duration getWalletTtl() {
        return walletTtl;
    }

    public void setWalletTtl(Duration walletTtl) {
        this.walletTtl = walletTtl;
    }

    public Duration getBidRequestTtl() {
        return bidRequestTtl;
    }

    public void setBidRequestTtl(Duration bidRequestTtl) {
        this.bidRequestTtl = bidRequestTtl;
    }

    public String getHotBidStreamKey() {
        return hotBidStreamKey;
    }

    public void setHotBidStreamKey(String hotBidStreamKey) {
        this.hotBidStreamKey = hotBidStreamKey;
    }

    public String getHotBidDeadLetterStreamKey() {
        return hotBidDeadLetterStreamKey;
    }

    public void setHotBidDeadLetterStreamKey(String hotBidDeadLetterStreamKey) {
        this.hotBidDeadLetterStreamKey = hotBidDeadLetterStreamKey;
    }

    public String getHotBidStreamGroup() {
        return hotBidStreamGroup;
    }

    public void setHotBidStreamGroup(String hotBidStreamGroup) {
        this.hotBidStreamGroup = hotBidStreamGroup;
    }

    public long getHotBidStreamMaxLength() {
        return hotBidStreamMaxLength;
    }

    public void setHotBidStreamMaxLength(long hotBidStreamMaxLength) {
        this.hotBidStreamMaxLength = hotBidStreamMaxLength;
    }

    public int getHotBidStreamBatchSize() {
        return hotBidStreamBatchSize;
    }

    public void setHotBidStreamBatchSize(int hotBidStreamBatchSize) {
        this.hotBidStreamBatchSize = Math.max(1, hotBidStreamBatchSize);
    }

    public long getHotBidStreamPollIntervalMs() {
        return hotBidStreamPollIntervalMs;
    }

    public void setHotBidStreamPollIntervalMs(long hotBidStreamPollIntervalMs) {
        this.hotBidStreamPollIntervalMs = Math.max(1, hotBidStreamPollIntervalMs);
    }

    public Duration getHotBidStreamPendingTimeout() {
        return hotBidStreamPendingTimeout;
    }

    public void setHotBidStreamPendingTimeout(Duration hotBidStreamPendingTimeout) {
        this.hotBidStreamPendingTimeout = hotBidStreamPendingTimeout;
    }

    public long getHotBidStreamMaxBacklog() {
        return hotBidStreamMaxBacklog;
    }

    public void setHotBidStreamMaxBacklog(long hotBidStreamMaxBacklog) {
        this.hotBidStreamMaxBacklog = Math.max(1, hotBidStreamMaxBacklog);
    }

    public String getHotBidBackpressureKey() {
        return hotBidBackpressureKey;
    }

    public void setHotBidBackpressureKey(String hotBidBackpressureKey) {
        this.hotBidBackpressureKey = hotBidBackpressureKey;
    }
}

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
    private Duration hotRoomWindow = Duration.ofHours(1);
    private Duration hotRoomBuffer = Duration.ofMinutes(10);
    private int hotAccessThreshold = 10;
    private Duration bidLockTtl = Duration.ofSeconds(5);

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

    public Duration getHotRoomWindow() {
        return hotRoomWindow;
    }

    public void setHotRoomWindow(Duration hotRoomWindow) {
        this.hotRoomWindow = hotRoomWindow;
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

    public Duration getBidLockTtl() {
        return bidLockTtl;
    }

    public void setBidLockTtl(Duration bidLockTtl) {
        this.bidLockTtl = bidLockTtl;
    }
}

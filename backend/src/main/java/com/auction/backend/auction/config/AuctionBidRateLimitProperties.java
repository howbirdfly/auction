package com.auction.backend.auction.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "auction.bid-rate-limit")
public class AuctionBidRateLimitProperties {

    private boolean enabled = true;
    private Duration userRoomInterval = Duration.ofMillis(800);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getUserRoomInterval() {
        return userRoomInterval;
    }

    public void setUserRoomInterval(Duration userRoomInterval) {
        this.userRoomInterval = userRoomInterval;
    }
}

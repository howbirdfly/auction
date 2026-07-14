package com.auction.backend.auction.service;

import com.auction.backend.auction.cache.AuctionCacheService;
import com.auction.backend.auction.config.AuctionCacheProperties;
import com.auction.backend.auction.dto.AuctionLeaderboardEntry;
import com.auction.backend.auction.dto.AuctionRoomSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
@ConditionalOnProperty(name = "auction.cache.redis.enabled", havingValue = "true")
public class RedisHotRoomManager implements HotRoomManager {

    private final StringRedisTemplate stringRedisTemplate;
    private final AuctionCacheService auctionCacheService;
    private final AuctionCacheProperties auctionCacheProperties;

    public RedisHotRoomManager(StringRedisTemplate stringRedisTemplate,
                               AuctionCacheService auctionCacheService,
                               AuctionCacheProperties auctionCacheProperties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.auctionCacheService = auctionCacheService;
        this.auctionCacheProperties = auctionCacheProperties;
    }

    @Override
    public boolean recordAccess(String roomId) {
        try {
            String key = accessKey(roomId, Instant.now().getEpochSecond());
            Long count = stringRedisTemplate.opsForValue().increment(key);
            stringRedisTemplate.expire(key, Duration.ofSeconds(3));
            return count != null && count >= auctionCacheProperties.getHotAccessThreshold();
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public boolean isHot(String roomId) {
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(modeKey(roomId)));
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public void markHot(AuctionRoomSnapshot snapshot, List<AuctionLeaderboardEntry> leaderboard) {
        try {
            Duration ttl = Duration.ofSeconds(Math.max(1, snapshot.secondsRemaining()))
                    .plus(auctionCacheProperties.getHotRoomBuffer());
            stringRedisTemplate.opsForValue().set(modeKey(snapshot.roomId()), "HOT", ttl);
            auctionCacheService.cacheRoom(snapshot);
            auctionCacheService.cacheLeaderboard(snapshot.roomId(), leaderboard, snapshot);
            auctionCacheService.cacheRecentBids(snapshot.roomId(), snapshot.recentBids(), snapshot);
        } catch (Exception ignored) {
            // Fall back to MySQL path when Redis is unavailable.
        }
    }

    @Override
    public void clear(String roomId) {
        try {
            stringRedisTemplate.delete(modeKey(roomId));
        } catch (Exception ignored) {
            // Ignore cleanup failures for hot room mode.
        }
    }

    private String modeKey(String roomId) {
        return "auction:room:" + roomId + ":mode";
    }

    private String accessKey(String roomId, long epochSecond) {
        return "auction:room:" + roomId + ":metrics:view:" + epochSecond;
    }
}

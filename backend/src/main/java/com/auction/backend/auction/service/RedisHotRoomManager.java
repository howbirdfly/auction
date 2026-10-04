package com.auction.backend.auction.service;

import com.auction.backend.auction.cache.AuctionCacheService;
import com.auction.backend.auction.config.AuctionCacheProperties;
import com.auction.backend.auction.dto.AuctionLeaderboardEntry;
import com.auction.backend.auction.dto.AuctionRoomSnapshot;
import com.auction.backend.auction.mapper.AuctionRoomMapper;
import com.auction.backend.auction.mapper.AuctionRoomRegistrationMapper;
import com.auction.backend.auction.model.AuctionRegistrationStatus;
import com.auction.backend.auction.model.AuctionRoom;
import com.auction.backend.auction.model.AuctionRoomRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
@ConditionalOnProperty(name = "auction.cache.redis.enabled", havingValue = "true")
public class RedisHotRoomManager implements HotRoomManager {

    private static final Logger log = LoggerFactory.getLogger(RedisHotRoomManager.class);
    private static final String ENGINE_REDIS = "REDIS";
    private static final String ENGINE_MYSQL = "MYSQL";

    private final StringRedisTemplate stringRedisTemplate;
    private final AuctionCacheService auctionCacheService;
    private final AuctionCacheProperties auctionCacheProperties;
    private final AuctionRoomRegistrationMapper auctionRoomRegistrationMapper;
    private final AuctionRoomMapper auctionRoomMapper;
    private final RedisScript<String> bidMetricScript;

    public RedisHotRoomManager(StringRedisTemplate stringRedisTemplate,
                               AuctionCacheService auctionCacheService,
                               AuctionCacheProperties auctionCacheProperties,
                               AuctionRoomRegistrationMapper auctionRoomRegistrationMapper,
                               AuctionRoomMapper auctionRoomMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.auctionCacheService = auctionCacheService;
        this.auctionCacheProperties = auctionCacheProperties;
        this.auctionRoomRegistrationMapper = auctionRoomRegistrationMapper;
        this.auctionRoomMapper = auctionRoomMapper;
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/auction_record_bid_metric.lua"));
        script.setResultType(String.class);
        this.bidMetricScript = script;
    }

    @Override
    public boolean recordAccess(String roomId) {
        try {
            String key = accessKey(roomId, Instant.now().getEpochSecond());
            Long count = stringRedisTemplate.opsForValue().increment(key);
            stringRedisTemplate.expire(key, Duration.ofSeconds(3));
            return count != null && count >= auctionCacheProperties.getHotAccessThreshold();
        } catch (Exception exception) {
            log.warn("Failed to record room access for {}", roomId, exception);
            return false;
        }
    }

    @Override
    public boolean recordBid(String roomId) {
        try {
            long nowSecond = Instant.now().getEpochSecond();
            String result = stringRedisTemplate.execute(
                    bidMetricScript,
                    List.of(bidMetricKey(roomId, nowSecond)),
                    bidMetricPrefix(roomId),
                    Long.toString(nowSecond),
                    Long.toString(auctionCacheProperties.getHotBidEnterWindow().toSeconds()),
                    Integer.toString(auctionCacheProperties.getHotBidEnterThreshold()),
                    Long.toString(auctionCacheProperties.getHotBidEnterWindow().plusSeconds(5).toSeconds())
            );
            return result != null && result.startsWith("1|");
        } catch (Exception exception) {
            log.warn("Failed to record bid metric for {}", roomId, exception);
            return false;
        }
    }

    @Override
    public boolean isHot(String roomId) {
        return status(roomId) == HotRoomStatus.HOT;
    }

    @Override
    public HotRoomStatus status(String roomId) {
        try {
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(modeKey(roomId)))) {
                return HotRoomStatus.HOT;
            }
            return databaseStatus(roomId);
        } catch (Exception exception) {
            log.warn("Failed to read hot room status for {}", roomId, exception);
            try {
                return databaseStatus(roomId);
            } catch (Exception databaseException) {
                return HotRoomStatus.REDIS_UNAVAILABLE;
            }
        }
    }

    @Override
    public boolean shouldStayHot(String roomId) {
        try {
            long bidCount = sumWindow(
                    roomId,
                    auctionCacheProperties.getHotBidExitWindow().toSeconds(),
                    true
            );
            long required = (long) auctionCacheProperties.getHotBidExitThreshold()
                    * auctionCacheProperties.getHotBidExitWindow().toSeconds();
            return bidCount >= required;
        } catch (Exception exception) {
            log.warn("Failed to evaluate hot room exit for {}", roomId, exception);
            return true;
        }
    }

    @Override
    public void markHot(AuctionRoomSnapshot snapshot, List<AuctionLeaderboardEntry> leaderboard) {
        try {
            auctionRoomMapper.updateEngineMode(snapshot.roomId(), ENGINE_REDIS);
        } catch (Exception exception) {
            log.error("Failed to persist REDIS engine mode for room {}", snapshot.roomId(), exception);
            return;
        }

        try {
            Duration ttl = Duration.ofSeconds(Math.max(1, snapshot.secondsRemaining()))
                    .plus(auctionCacheProperties.getHotRoomBuffer());
            stringRedisTemplate.opsForValue().set(modeKey(snapshot.roomId()), "HOT", ttl);
            auctionCacheService.cacheRoom(snapshot);
            auctionCacheService.cacheLeaderboard(snapshot.roomId(), leaderboard, snapshot);
            auctionCacheService.cacheRecentBids(snapshot.roomId(), snapshot.recentBids(), snapshot);
            refreshQualifications(snapshot.roomId(), ttl);
        } catch (Exception exception) {
            log.error("Failed to cache HOT room {}, requests will fail closed", snapshot.roomId(), exception);
        }
    }

    @Override
    public void cacheQualification(String roomId, String userId) {
        try {
            if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(modeKey(roomId)))) {
                return;
            }
            String key = qualificationKey(roomId);
            stringRedisTemplate.opsForSet().add(key, userId);
            stringRedisTemplate.expire(key, auctionCacheProperties.getRoomTtl());
        } catch (Exception exception) {
            log.warn("Failed to cache qualification for room {} user {}", roomId, userId, exception);
        }
    }

    @Override
    public void clear(String roomId) {
        try {
            stringRedisTemplate.delete(List.of(modeKey(roomId), qualificationKey(roomId)));
        } catch (Exception exception) {
            log.warn("Failed to clear hot room cache for {}", roomId, exception);
        }
        try {
            auctionRoomMapper.updateEngineMode(roomId, ENGINE_MYSQL);
        } catch (Exception exception) {
            log.error("Failed to persist MYSQL engine mode for room {}", roomId, exception);
        }
    }

    private HotRoomStatus databaseStatus(String roomId) {
        AuctionRoom room = auctionRoomMapper.findById(roomId);
        return room != null && ENGINE_REDIS.equalsIgnoreCase(room.getEngineMode())
                ? HotRoomStatus.REDIS_UNAVAILABLE
                : HotRoomStatus.COLD;
    }

    private long sumWindow(String roomId, long seconds, boolean bidMetric) {
        long now = Instant.now().getEpochSecond();
        List<String> keys = java.util.stream.LongStream.rangeClosed(0, Math.max(0, seconds - 1))
                .mapToObj(offset -> bidMetric
                        ? bidMetricKey(roomId, now - offset)
                        : accessKey(roomId, now - offset))
                .toList();
        List<String> values = stringRedisTemplate.opsForValue().multiGet(keys);
        if (values == null) {
            return 0L;
        }
        long total = 0L;
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                total += Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                // Ignore malformed metric values and keep evaluating the remaining window.
            }
        }
        return total;
    }

    private void refreshQualifications(String roomId, Duration ttl) {
        String key = qualificationKey(roomId);
        List<String> qualifiedUsers = auctionRoomRegistrationMapper.findAllByRoomId(roomId).stream()
                .filter(registration -> registration.getStatus() == AuctionRegistrationStatus.LOCKED)
                .map(AuctionRoomRegistration::getUserId)
                .toList();
        if (qualifiedUsers.isEmpty()) {
            stringRedisTemplate.delete(key);
            return;
        }

        String temporaryKey = key + ":refresh:" + System.nanoTime();
        stringRedisTemplate.opsForSet().add(temporaryKey, qualifiedUsers.toArray(String[]::new));
        stringRedisTemplate.expire(temporaryKey, ttl);
        stringRedisTemplate.rename(temporaryKey, key);
    }

    private String modeKey(String roomId) {
        return "auction:room:" + roomId + ":mode";
    }

    private String accessKey(String roomId, long epochSecond) {
        return "auction:room:" + roomId + ":metrics:view:" + epochSecond;
    }

    private String bidMetricKey(String roomId, long epochSecond) {
        return bidMetricPrefix(roomId) + epochSecond;
    }

    private String bidMetricPrefix(String roomId) {
        return "auction:room:" + roomId + ":metrics:bid:";
    }

    private String qualificationKey(String roomId) {
        return "auction:room:" + roomId + ":qualified";
    }
}

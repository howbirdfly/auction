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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

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
    private final Map<String, ConcurrentSkipListMap<Long, AtomicLong>> bidMetrics = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentSkipListMap<Long, AtomicLong>> viewMetrics = new ConcurrentHashMap<>();

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
    }

    @Override
    public boolean recordAccess(String roomId) {
        long second = Instant.now().getEpochSecond();
        recordMetric(viewMetrics, roomId, second, Duration.ofSeconds(3));
        return sumWindow(viewMetrics, roomId, 1, Duration.ofSeconds(3))
                >= auctionCacheProperties.getHotAccessThreshold();
    }

    @Override
    public boolean recordBid(String roomId) {
        long second = Instant.now().getEpochSecond();
        recordMetric(bidMetrics, roomId, second, auctionCacheProperties.getHotBidEnterWindow());
        long bidCount = sumWindow(
                bidMetrics,
                roomId,
                auctionCacheProperties.getHotBidEnterWindow().toSeconds(),
                auctionCacheProperties.getHotBidEnterWindow()
        );
        long required = (long) auctionCacheProperties.getHotBidEnterThreshold()
                * auctionCacheProperties.getHotBidEnterWindow().toSeconds();
        return bidCount >= required;
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
                    bidMetrics,
                    roomId,
                    auctionCacheProperties.getHotBidExitWindow().toSeconds(),
                    auctionCacheProperties.getHotBidExitWindow()
            );
            if (bidCount == 0L) {
                return true;
            }
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
        bidMetrics.remove(roomId);
        viewMetrics.remove(roomId);
        try {
            auctionRoomMapper.updateEngineMode(roomId, ENGINE_MYSQL);
        } catch (Exception exception) {
            log.error("Failed to persist MYSQL engine mode for room {}", roomId, exception);
            return;
        }
        try {
            stringRedisTemplate.delete(List.of(modeKey(roomId), qualificationKey(roomId)));
        } catch (Exception exception) {
            log.warn("Failed to clear hot room cache for {}", roomId, exception);
        }
    }

    private HotRoomStatus databaseStatus(String roomId) {
        AuctionRoom room = auctionRoomMapper.findById(roomId);
        return room != null && ENGINE_REDIS.equalsIgnoreCase(room.getEngineMode())
                ? HotRoomStatus.REDIS_UNAVAILABLE
                : HotRoomStatus.COLD;
    }

    private void recordMetric(Map<String, ConcurrentSkipListMap<Long, AtomicLong>> metrics,
                              String roomId,
                              long second,
                              Duration retention) {
        ConcurrentSkipListMap<Long, AtomicLong> roomMetrics = metrics.computeIfAbsent(
                roomId,
                ignored -> new ConcurrentSkipListMap<>()
        );
        roomMetrics.computeIfAbsent(second, ignored -> new AtomicLong()).incrementAndGet();
        long cutoff = second - Math.max(5, retention.toSeconds() * 2);
        roomMetrics.headMap(cutoff, true).clear();
    }

    private long sumWindow(Map<String, ConcurrentSkipListMap<Long, AtomicLong>> metrics,
                           String roomId,
                           long seconds,
                           Duration retention) {
        ConcurrentSkipListMap<Long, AtomicLong> roomMetrics = metrics.get(roomId);
        if (roomMetrics == null || roomMetrics.isEmpty()) {
            return 0L;
        }
        long now = Instant.now().getEpochSecond();
        long cutoff = now - Math.max(0, seconds - 1);
        long total = 0L;
        for (Map.Entry<Long, AtomicLong> entry : roomMetrics.tailMap(cutoff).entrySet()) {
            total += entry.getValue().get();
        }
        roomMetrics.headMap(now - Math.max(5, retention.toSeconds() * 2), true).clear();
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

    private String qualificationKey(String roomId) {
        return "auction:room:" + roomId + ":qualified";
    }
}

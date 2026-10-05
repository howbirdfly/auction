package com.auction.backend.auction.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
@ConditionalOnProperty(name = "auction.cache.redis.enabled", havingValue = "true")
public class RedisHotRoomRateTracker {

    private static final Logger log = LoggerFactory.getLogger(RedisHotRoomRateTracker.class);
    private static final String BID_METRIC = "bid";
    private static final String VIEW_METRIC = "view";
    private static final long MIN_RETENTION_SECONDS = 30L;

    private static final RedisScript<Long> RECORD_AND_COUNT_SCRIPT = new DefaultRedisScript<>("""
            local metric = ARGV[1]
            local ttlSeconds = tonumber(ARGV[2])
            local windowSeconds = tonumber(ARGV[3])
            local currentSecond = tonumber(redis.call("TIME")[1])
            local field = metric .. ":" .. tostring(currentSecond)

            redis.call("HINCRBY", KEYS[1], field, 1)
            redis.call("EXPIRE", KEYS[1], ttlSeconds)

            local total = 0
            for offset = 0, windowSeconds - 1 do
                local value = redis.call(
                    "HGET",
                    KEYS[1],
                    metric .. ":" .. tostring(currentSecond - offset)
                )
                if value then
                    total = total + tonumber(value)
                end
            end
            return total
            """, Long.class);

    private static final RedisScript<Long> COUNT_SCRIPT = new DefaultRedisScript<>("""
            local metric = ARGV[1]
            local windowSeconds = tonumber(ARGV[2])
            local currentSecond = tonumber(redis.call("TIME")[1])

            local total = 0
            for offset = 0, windowSeconds - 1 do
                local value = redis.call(
                    "HGET",
                    KEYS[1],
                    metric .. ":" .. tostring(currentSecond - offset)
                )
                if value then
                    total = total + tonumber(value)
                end
            end
            return total
            """, Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    public RedisHotRoomRateTracker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long recordBid(String roomId, Duration window) {
        return recordAndCount(roomId, BID_METRIC, window);
    }

    public long countBids(String roomId, Duration window) {
        return count(roomId, BID_METRIC, window);
    }

    public long recordView(String roomId) {
        return recordAndCount(roomId, VIEW_METRIC, Duration.ofSeconds(1));
    }

    public void clear(String roomId) {
        if (isBlank(roomId)) {
            return;
        }
        try {
            stringRedisTemplate.delete(rateKey(roomId));
        } catch (RuntimeException exception) {
            log.warn("Failed to clear hot room rate metrics for {}", roomId, exception);
        }
    }

    long recordAndCount(String roomId, String metric, Duration window) {
        if (isBlank(roomId) || isBlank(metric) || window == null) {
            return 0L;
        }
        long windowSeconds = windowSeconds(window);
        try {
            Long count = stringRedisTemplate.execute(
                    RECORD_AND_COUNT_SCRIPT,
                    List.of(rateKey(roomId)),
                    metric,
                    Long.toString(retentionSeconds(windowSeconds)),
                    Long.toString(windowSeconds)
            );
            return count == null ? 0L : count;
        } catch (RuntimeException exception) {
            log.warn("Failed to record hot room rate metric for {}", roomId, exception);
            return 0L;
        }
    }

    long count(String roomId, String metric, Duration window) {
        if (isBlank(roomId) || isBlank(metric) || window == null) {
            return 0L;
        }
        try {
            Long count = stringRedisTemplate.execute(
                    COUNT_SCRIPT,
                    List.of(rateKey(roomId)),
                    metric,
                    Long.toString(windowSeconds(window))
            );
            return count == null ? 0L : count;
        } catch (RuntimeException exception) {
            log.warn("Failed to read hot room rate metric for {}", roomId, exception);
            return 0L;
        }
    }

    private String rateKey(String roomId) {
        return "auction:room:" + roomId.trim() + ":hot-rate";
    }

    private long windowSeconds(Duration window) {
        return Math.max(1L, window.toSeconds());
    }

    private long retentionSeconds(long windowSeconds) {
        return Math.max(MIN_RETENTION_SECONDS, windowSeconds + MIN_RETENTION_SECONDS);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

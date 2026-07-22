package com.auction.backend.auction.service;

import com.auction.backend.auction.config.AuctionBidRateLimitProperties;
import com.auction.backend.auction.config.AuctionCacheProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class BidRateLimitService {

    private final AuctionBidRateLimitProperties properties;
    private final AuctionCacheProperties auctionCacheProperties;
    private final ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider;
    private final Map<String, Long> localWindows = new ConcurrentHashMap<>();

    public BidRateLimitService(AuctionBidRateLimitProperties properties,
                               AuctionCacheProperties auctionCacheProperties,
                               ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider) {
        this.properties = properties;
        this.auctionCacheProperties = auctionCacheProperties;
        this.stringRedisTemplateProvider = stringRedisTemplateProvider;
    }

    public void assertAllowed(String roomId, String userId) {
        if (!properties.isEnabled()) {
            return;
        }

        Duration interval = properties.getUserRoomInterval();
        if (interval == null || interval.isZero() || interval.isNegative()) {
            return;
        }

        String limitKey = buildLimitKey(roomId, userId);
        if (tryAcquireRedis(limitKey, interval)) {
            return;
        }

        tryAcquireLocal(limitKey, interval);
    }

    @Scheduled(fixedDelay = 60000)
    public void cleanupLocalWindows() {
        long now = System.currentTimeMillis();
        localWindows.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    private boolean tryAcquireRedis(String limitKey, Duration interval) {
        if (!auctionCacheProperties.isEnabled()) {
            return false;
        }

        StringRedisTemplate stringRedisTemplate = stringRedisTemplateProvider.getIfAvailable();
        if (stringRedisTemplate == null) {
            return false;
        }

        try {
            Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(limitKey, "1", interval);
            if (Boolean.TRUE.equals(acquired)) {
                return true;
            }

            long remainingMillis = resolveRedisRemainingMillis(stringRedisTemplate, limitKey, interval);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, buildRateLimitMessage(remainingMillis));
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void tryAcquireLocal(String limitKey, Duration interval) {
        long now = System.currentTimeMillis();
        long intervalMillis = Math.max(1L, interval.toMillis());
        AtomicBoolean allowed = new AtomicBoolean(false);

        Long expiresAt = localWindows.compute(limitKey, (key, currentExpiresAt) -> {
            if (currentExpiresAt == null || currentExpiresAt <= now) {
                allowed.set(true);
                return now + intervalMillis;
            }
            return currentExpiresAt;
        });

        if (allowed.get()) {
            return;
        }

        long remainingMillis = expiresAt == null ? intervalMillis : Math.max(1L, expiresAt - now);
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, buildRateLimitMessage(remainingMillis));
    }

    private long resolveRedisRemainingMillis(StringRedisTemplate stringRedisTemplate,
                                             String limitKey,
                                             Duration interval) {
        Long expireSeconds = stringRedisTemplate.getExpire(limitKey);
        if (expireSeconds == null || expireSeconds < 0) {
            return Math.max(1L, interval.toMillis());
        }
        return Math.max(1L, expireSeconds * 1000);
    }

    private String buildRateLimitMessage(long remainingMillis) {
        if (remainingMillis < 1000) {
            return "出价过快，请稍后再试";
        }

        double seconds = remainingMillis / 1000.0;
        return String.format("出价过快，请 %.1f 秒后再试", seconds);
    }

    private String buildLimitKey(String roomId, String userId) {
        return "auction:bid-rate-limit:" + roomId + ":" + userId;
    }
}

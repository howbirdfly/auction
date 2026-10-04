package com.auction.backend.auction.service;

import com.auction.backend.auction.cache.AuctionCacheService;
import com.auction.backend.auction.config.AuctionBidRateLimitProperties;
import com.auction.backend.auction.config.AuctionCacheProperties;
import com.auction.backend.auction.dto.AuctionRoomSnapshot;
import com.auction.backend.auction.dto.BidRequest;
import com.auction.backend.auction.model.AuctionRoom;
import com.auction.backend.auction.model.AuctionStatus;
import com.auction.backend.user.service.HotWalletCacheService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "auction.cache.redis.enabled", havingValue = "true")
public class RedisBidEngine implements BidEngine {

    private final AuctionRoomReadService auctionRoomReadService;
    private final AuctionCacheService auctionCacheService;
    private final StringRedisTemplate stringRedisTemplate;
    private final AuctionCacheProperties auctionCacheProperties;
    private final AuctionBidRateLimitProperties bidRateLimitProperties;
    private final HotRoomManager hotRoomManager;
    private final RedisScript<String> hotBidScript;
    private final AuctionSettlementService auctionSettlementService;
    private final HotWalletCacheService hotWalletCacheService;

    public RedisBidEngine(AuctionRoomReadService auctionRoomReadService,
                          AuctionCacheService auctionCacheService,
                          StringRedisTemplate stringRedisTemplate,
                          AuctionCacheProperties auctionCacheProperties,
                          AuctionBidRateLimitProperties bidRateLimitProperties,
                          HotRoomManager hotRoomManager,
                          AuctionSettlementService auctionSettlementService,
                          HotWalletCacheService hotWalletCacheService) {
        this.auctionRoomReadService = auctionRoomReadService;
        this.auctionCacheService = auctionCacheService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.auctionCacheProperties = auctionCacheProperties;
        this.bidRateLimitProperties = bidRateLimitProperties;
        this.hotRoomManager = hotRoomManager;
        this.auctionSettlementService = auctionSettlementService;
        this.hotWalletCacheService = hotWalletCacheService;
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/auction_hot_bid.lua"));
        script.setResultType(String.class);
        this.hotBidScript = script;
    }

    @Override
    public AuctionRoomSnapshot placeBid(String roomId, BidRequest request) {
        AuctionRoomSnapshot cachedRoom = auctionCacheService.getRoom(roomId)
                .orElseGet(() -> prewarmHotRoomState(roomId));
        Instant now = Instant.now();
        validateRoomOpen(cachedRoom, now);

        String expectedPreviousLeaderUserId = !cachedRoom.recentBids().isEmpty()
                ? cachedRoom.recentBids().get(0).userId()
                : null;
        hotWalletCacheService.prewarmAccountIfNeeded(request.userId());
        if (expectedPreviousLeaderUserId != null && !expectedPreviousLeaderUserId.isBlank()) {
            hotWalletCacheService.prewarmAccountIfNeeded(expectedPreviousLeaderUserId);
        }

        long nowMillis = now.toEpochMilli();
        String eventId = UUID.randomUUID().toString();
        String result = executeHotBidScript(roomId, request, nowMillis, eventId);
        if (result != null && result.startsWith("ERR|ROOM_MISSING")) {
            prewarmHotRoomState(roomId);
            result = executeHotBidScript(roomId, request, nowMillis, eventId);
        }

        if (result != null && result.startsWith("DUP|")) {
            return auctionCacheService.getRoom(roomId)
                    .orElseGet(() -> auctionRoomReadService.getRoom(roomId));
        }

        validateHotBidResult(roomId, result);
        return auctionCacheService.getRoom(roomId)
                .orElseGet(() -> auctionRoomReadService.getRoom(roomId));
    }

    private void validateRoomOpen(AuctionRoomSnapshot room, Instant now) {
        if (room.status() == AuctionStatus.CLOSED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "auction already closed");
        }

        if (now.isAfter(room.endsAt())) {
            AuctionRoom dbRoom = auctionRoomReadService.findRoom(room.roomId());
            auctionRoomReadService.closeRoom(dbRoom);
            auctionSettlementService.settle(dbRoom);
            hotRoomManager.clear(room.roomId());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "auction already closed");
        }
    }

    private AuctionRoomSnapshot prewarmHotRoomState(String roomId) {
        AuctionRoomSnapshot snapshot = auctionRoomReadService.getRoom(roomId);
        auctionCacheService.cacheRoom(snapshot);
        auctionCacheService.cacheLeaderboard(
                roomId,
                auctionCacheService.getLeaderboard(roomId).orElseGet(() -> auctionRoomReadService.loadLeaderboard(roomId)),
                snapshot
        );
        auctionCacheService.cacheRecentBids(
                roomId,
                auctionCacheService.getRecentBids(roomId).orElse(snapshot.recentBids()),
                snapshot
        );
        return snapshot;
    }

    private String executeHotBidScript(String roomId,
                                       BidRequest request,
                                       long nowMillis,
                                       String eventId) {
        long rateLimitMillis = bidRateLimitProperties.isEnabled()
                && bidRateLimitProperties.getUserRoomInterval() != null
                ? Math.max(0L, bidRateLimitProperties.getUserRoomInterval().toMillis())
                : 0L;

        return stringRedisTemplate.execute(
                hotBidScript,
                List.of(
                        "auction:room:" + roomId + ":hot-state",
                        "auction:room:" + roomId + ":leaderboard",
                        "auction:room:" + roomId + ":leaderboard:profile",
                        "auction:room:" + roomId + ":recent-bids",
                        "auction:room:" + roomId + ":qualified",
                        "auction:bid-request:" + request.requestId(),
                        "auction:bid-rate-limit:" + roomId + ":" + request.userId(),
                        auctionCacheProperties.getHotBidStreamKey()
                ),
                AuctionStatus.CLOSED.name(),
                Long.toString(nowMillis),
                request.userId(),
                request.nickname(),
                request.amount().toPlainString(),
                Long.toString(auctionCacheProperties.getHotRoomBuffer().toSeconds()),
                hotWalletCacheService.walletKeyPrefix(),
                Long.toString(hotWalletCacheService.walletTtlSeconds()),
                request.requestId(),
                Long.toString(rateLimitMillis),
                Long.toString(auctionCacheProperties.getBidRequestTtl().toMillis()),
                eventId,
                roomId,
                Long.toString(auctionCacheProperties.getHotBidStreamMaxLength())
        );
    }

    private void validateHotBidResult(String roomId, String result) {
        if (result == null || result.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "redis bid engine is unavailable");
        }

        if (result.startsWith("OK|")) {
            return;
        }

        String[] parts = result.split("\\|");
        if (parts.length >= 2 && "ERR".equals(parts[0])) {
            String errorCode = parts[1];
            switch (errorCode) {
                case "ROOM_MISSING" -> throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "hot room state is not ready for room " + roomId
                );
                case "ROOM_CLOSED", "ROOM_EXPIRED" -> {
                    AuctionRoom dbRoom = auctionRoomReadService.findRoom(roomId);
                    auctionRoomReadService.closeRoom(dbRoom);
                    auctionSettlementService.settle(dbRoom);
                    hotRoomManager.clear(roomId);
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "auction already closed");
                }
                case "RATE_LIMITED" -> throw new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "bid requests are too frequent"
                );
                case "NOT_QUALIFIED" -> throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "bidder is not qualified for this auction"
                );
                case "BID_TOO_LOW" -> {
                    String minBid = parts.length >= 3 ? parts[2] : "0.00";
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "bid must be greater than or equal to " + minBid
                    );
                }
                case "BIDDER_WALLET_MISSING" -> throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "hot wallet cache is not ready"
                );
                case "INSUFFICIENT_FUNDS" -> throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "insufficient funds"
                );
                case "REQUEST_ALREADY_USED" -> throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "bid requestId has already been used"
                );
                default -> throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "redis bid engine is unavailable"
                );
            }
        }

        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "redis bid engine is unavailable");
    }
}

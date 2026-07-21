package com.auction.backend.auction.service;

import com.auction.backend.auction.cache.AuctionCacheService;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
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
    private final HotRoomManager hotRoomManager;
    private final RedisScript<String> hotBidScript;
    private final AuctionQualificationService auctionQualificationService;
    private final HotBidPersistenceGateway hotBidPersistenceGateway;
    private final AuctionWalletService auctionWalletService;
    private final HotWalletCacheService hotWalletCacheService;

    public RedisBidEngine(AuctionRoomReadService auctionRoomReadService,
                          AuctionCacheService auctionCacheService,
                          StringRedisTemplate stringRedisTemplate,
                          AuctionCacheProperties auctionCacheProperties,
                          HotRoomManager hotRoomManager,
                          AuctionQualificationService auctionQualificationService,
                          HotBidPersistenceGateway hotBidPersistenceGateway,
                          AuctionWalletService auctionWalletService,
                          HotWalletCacheService hotWalletCacheService) {
        this.auctionRoomReadService = auctionRoomReadService;
        this.auctionCacheService = auctionCacheService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.auctionCacheProperties = auctionCacheProperties;
        this.hotRoomManager = hotRoomManager;
        this.auctionQualificationService = auctionQualificationService;
        this.hotBidPersistenceGateway = hotBidPersistenceGateway;
        this.auctionWalletService = auctionWalletService;
        this.hotWalletCacheService = hotWalletCacheService;
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/auction_hot_bid.lua"));
        script.setResultType(String.class);
        this.hotBidScript = script;
    }

    @Override
    @Transactional
    public AuctionRoomSnapshot placeBid(String roomId, BidRequest request) {
        AuctionRoomSnapshot cachedRoom = auctionCacheService.getRoom(roomId)
                .orElseGet(() -> prewarmHotRoomState(roomId));
        Instant now = Instant.now();
        validateRoomOpen(cachedRoom, now);

        AuctionRoom qualificationRoom = auctionRoomReadService.findRoom(roomId);
        auctionQualificationService.assertEligibleToBid(qualificationRoom, request.userId());

        String expectedPreviousLeaderUserId = !cachedRoom.recentBids().isEmpty()
                ? cachedRoom.recentBids().get(0).userId()
                : null;
        hotWalletCacheService.prewarmAccountIfNeeded(request.userId());
        if (expectedPreviousLeaderUserId != null && !expectedPreviousLeaderUserId.isBlank()) {
            hotWalletCacheService.prewarmAccountIfNeeded(expectedPreviousLeaderUserId);
        }

        long nowMillis = now.toEpochMilli();
        String result = executeHotBidScript(roomId, request, nowMillis);
        if (result != null && result.startsWith("ERR|ROOM_MISSING")) {
            prewarmHotRoomState(roomId);
            result = executeHotBidScript(roomId, request, nowMillis);
        }

        HotBidResult hotBidResult = parseHotBidResult(roomId, result);

        AuctionRoom room = auctionRoomReadService.findRoom(roomId);
        Instant bidTime = Instant.ofEpochMilli(nowMillis);
        room.setCurrentPrice(request.amount());
        room.setLeaderUserId(request.userId());
        room.setLeaderNickname(request.nickname());
        room.setEndsAt(Instant.ofEpochMilli(hotBidResult.endsAtEpochMilli()));
        room.setVersion(hotBidResult.roomVersion());

        hotBidPersistenceGateway.persist(new HotBidPersistenceMessage(
                UUID.randomUUID().toString(),
                room.getRoomId(),
                request.userId(),
                request.nickname(),
                request.amount(),
                hotBidResult.previousLeaderUserId(),
                hotBidResult.previousAmount(),
                hotBidResult.roomVersion(),
                bidTime,
                room.getEndsAt(),
                room.getStatus()
        ));

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
            auctionWalletService.settleRoom(dbRoom.getRoomId(), dbRoom.getLeaderUserId(), dbRoom.getCurrentPrice());
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

    private String executeHotBidScript(String roomId, BidRequest request, long nowMillis) {
        return stringRedisTemplate.execute(
                hotBidScript,
                List.of(
                        "auction:room:" + roomId + ":hot-state",
                        "auction:room:" + roomId + ":leaderboard",
                        "auction:room:" + roomId + ":leaderboard:profile",
                        "auction:room:" + roomId + ":recent-bids"
                ),
                AuctionStatus.CLOSED.name(),
                Long.toString(nowMillis),
                request.userId(),
                request.nickname(),
                request.amount().toPlainString(),
                Long.toString(auctionCacheProperties.getHotRoomBuffer().toSeconds()),
                hotWalletCacheService.walletKeyPrefix(),
                Long.toString(hotWalletCacheService.walletTtlSeconds())
        );
    }

    private HotBidResult parseHotBidResult(String roomId, String result) {
        if (result == null || result.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "redis bid engine is unavailable");
        }

        String[] parts = result.split("\\|");
        if ("OK".equals(parts[0]) && parts.length >= 7) {
            return new HotBidResult(
                    Long.parseLong(parts[1]),
                    Integer.parseInt(parts[2]),
                    new BigDecimal(parts[3]),
                    Long.parseLong(parts[4]),
                    parts[5].isBlank() ? null : parts[5],
                    new BigDecimal(parts[6])
            );
        }

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
                    auctionWalletService.settleRoom(dbRoom.getRoomId(), dbRoom.getLeaderUserId(), dbRoom.getCurrentPrice());
                    hotRoomManager.clear(roomId);
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "auction already closed");
                }
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
                        "余额不足，请先充值后再出价"
                );
                default -> throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "redis bid engine is unavailable"
                );
            }
        }

        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "redis bid engine is unavailable");
    }

    private record HotBidResult(
            long endsAtEpochMilli,
            int bidCount,
            BigDecimal minNextBid,
            long roomVersion,
            String previousLeaderUserId,
            BigDecimal previousAmount
    ) {
    }
}

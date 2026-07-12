package com.auction.backend.auction.service;

import com.auction.backend.auction.cache.AuctionCacheService;
import com.auction.backend.auction.config.AuctionCacheProperties;
import com.auction.backend.auction.dto.AuctionRoomSnapshot;
import com.auction.backend.auction.dto.BidRequest;
import com.auction.backend.auction.mapper.AuctionBidRecordMapper;
import com.auction.backend.auction.mapper.AuctionRoomMapper;
import com.auction.backend.auction.model.AuctionBidRecordEntity;
import com.auction.backend.auction.model.AuctionRoom;
import com.auction.backend.auction.model.AuctionStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "auction.cache.redis.enabled", havingValue = "true")
public class RedisBidEngine implements BidEngine {

    private static final long LAST_SECOND_EXTENSION_WINDOW = 10;
    private static final long LAST_SECOND_EXTENSION_SECONDS = 15;

    private final AuctionRoomReadService auctionRoomReadService;
    private final AuctionRoomMapper auctionRoomMapper;
    private final AuctionBidRecordMapper auctionBidRecordMapper;
    private final AuctionCacheService auctionCacheService;
    private final StringRedisTemplate stringRedisTemplate;
    private final AuctionCacheProperties auctionCacheProperties;

    public RedisBidEngine(AuctionRoomReadService auctionRoomReadService,
                          AuctionRoomMapper auctionRoomMapper,
                          AuctionBidRecordMapper auctionBidRecordMapper,
                          AuctionCacheService auctionCacheService,
                          StringRedisTemplate stringRedisTemplate,
                          AuctionCacheProperties auctionCacheProperties) {
        this.auctionRoomReadService = auctionRoomReadService;
        this.auctionRoomMapper = auctionRoomMapper;
        this.auctionBidRecordMapper = auctionBidRecordMapper;
        this.auctionCacheService = auctionCacheService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.auctionCacheProperties = auctionCacheProperties;
    }

    @Override
    @Transactional
    public AuctionRoomSnapshot placeBid(String roomId, BidRequest request) {
        String lockKey = "auction:room:" + roomId + ":bid:lock";
        String lockToken = UUID.randomUUID().toString();
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockToken, auctionCacheProperties.getBidLockTtl());

        if (!Boolean.TRUE.equals(locked)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "room is busy, please retry");
        }

        try {
            AuctionRoomSnapshot cachedRoom = auctionCacheService.getRoom(roomId)
                    .orElseGet(() -> auctionRoomReadService.getRoom(roomId));
            validateRoomOpen(cachedRoom, Instant.now());

            BigDecimal minNextBid = auctionRoomReadService.calculateMinNextBid(cachedRoom);
            if (request.amount().compareTo(minNextBid) < 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "bid must be greater than or equal to " + minNextBid
                );
            }

            AuctionRoom room = auctionRoomReadService.findRoom(roomId);
            Instant now = Instant.now();
            room.setCurrentPrice(request.amount());
            room.setLeaderUserId(request.userId());
            room.setLeaderNickname(request.nickname());

            auctionBidRecordMapper.insert(new AuctionBidRecordEntity(
                    room.getRoomId(),
                    request.userId(),
                    request.nickname(),
                    request.amount(),
                    now
            ));

            long secondsRemaining = Duration.between(now, cachedRoom.endsAt()).toSeconds();
            if (secondsRemaining <= LAST_SECOND_EXTENSION_WINDOW) {
                room.setEndsAt(room.getEndsAt().plusSeconds(LAST_SECOND_EXTENSION_SECONDS));
            }

            auctionRoomMapper.updateAfterBid(room);
            AuctionRoomSnapshot snapshot = auctionRoomReadService.toSnapshot(room, true);
            auctionCacheService.cacheRoom(snapshot);
            auctionCacheService.recordBid(roomId, request.userId(), request.nickname(), request.amount(), snapshot);
            return snapshot;
        } finally {
            try {
                String currentToken = stringRedisTemplate.opsForValue().get(lockKey);
                if (lockToken.equals(currentToken)) {
                    stringRedisTemplate.delete(lockKey);
                }
            } catch (Exception ignored) {
                // Let the short TTL clean up the lock if Redis is transiently unavailable.
            }
        }
    }

    private void validateRoomOpen(AuctionRoomSnapshot room, Instant now) {
        if (room.status() == AuctionStatus.CLOSED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "auction already closed");
        }

        if (now.isAfter(room.endsAt())) {
            AuctionRoom dbRoom = auctionRoomReadService.findRoom(room.roomId());
            auctionRoomReadService.closeRoom(dbRoom);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "auction already closed");
        }
    }
}

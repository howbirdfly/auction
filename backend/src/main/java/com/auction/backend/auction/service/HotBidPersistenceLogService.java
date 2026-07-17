package com.auction.backend.auction.service;

import com.auction.backend.auction.mapper.AuctionBidPersistenceLogMapper;
import com.auction.backend.auction.model.AuctionBidPersistenceLog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class HotBidPersistenceLogService {

    private static final int MAX_ERROR_LENGTH = 255;

    private final AuctionBidPersistenceLogMapper auctionBidPersistenceLogMapper;

    public HotBidPersistenceLogService(AuctionBidPersistenceLogMapper auctionBidPersistenceLogMapper) {
        this.auctionBidPersistenceLogMapper = auctionBidPersistenceLogMapper;
    }

    @Transactional
    public void recordQueued(HotBidPersistenceMessage message) {
        Instant now = Instant.now();
        AuctionBidPersistenceLog existing = auctionBidPersistenceLogMapper.findByEventId(message.eventId());
        if (existing != null) {
            auctionBidPersistenceLogMapper.updateStatus(message.eventId(), "QUEUED", null, null, now);
            return;
        }

        AuctionBidPersistenceLog log = new AuctionBidPersistenceLog();
        log.setEventId(message.eventId());
        log.setRoomId(message.roomId());
        log.setUserId(message.userId());
        log.setAmount(message.amount());
        log.setStatus("QUEUED");
        log.setAttemptCount(0);
        log.setCreatedAt(now);
        log.setUpdatedAt(now);
        auctionBidPersistenceLogMapper.insert(log);
    }

    @Transactional
    public void markProcessing(HotBidPersistenceMessage message) {
        recordQueued(message);
        Instant now = Instant.now();
        auctionBidPersistenceLogMapper.incrementAttemptCount(message.eventId(), now);
        auctionBidPersistenceLogMapper.updateStatus(message.eventId(), "PROCESSING", null, null, now);
    }

    @Transactional
    public void markSuccess(HotBidPersistenceMessage message) {
        recordQueued(message);
        Instant now = Instant.now();
        auctionBidPersistenceLogMapper.updateStatus(message.eventId(), "SUCCESS", null, now, now);
    }

    @Transactional
    public void markFailed(HotBidPersistenceMessage message, Throwable throwable) {
        recordQueued(message);
        auctionBidPersistenceLogMapper.updateStatus(
                message.eventId(),
                "FAILED",
                truncateError(throwable == null ? null : throwable.getMessage()),
                null,
                Instant.now()
        );
    }

    @Transactional
    public void markFallbackDirect(HotBidPersistenceMessage message, Throwable throwable) {
        recordQueued(message);
        auctionBidPersistenceLogMapper.updateStatus(
                message.eventId(),
                "FALLBACK_DIRECT",
                truncateError(throwable == null ? null : throwable.getMessage()),
                null,
                Instant.now()
        );
    }

    private String truncateError(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return null;
        }
        return errorMessage.length() <= MAX_ERROR_LENGTH
                ? errorMessage
                : errorMessage.substring(0, MAX_ERROR_LENGTH);
    }
}

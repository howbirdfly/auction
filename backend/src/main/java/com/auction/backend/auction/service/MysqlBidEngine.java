package com.auction.backend.auction.service;

import com.auction.backend.auction.cache.AuctionCacheService;
import com.auction.backend.auction.dto.AuctionRoomSnapshot;
import com.auction.backend.auction.dto.BidRequest;
import com.auction.backend.auction.mapper.AuctionBidRecordMapper;
import com.auction.backend.auction.mapper.AuctionRoomMapper;
import com.auction.backend.auction.model.AuctionBidRecordEntity;
import com.auction.backend.auction.model.AuctionRoom;
import com.auction.backend.auction.model.AuctionStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

@Service
public class MysqlBidEngine implements BidEngine {

    private final AuctionRoomReadService auctionRoomReadService;
    private final AuctionRoomMapper auctionRoomMapper;
    private final AuctionBidRecordMapper auctionBidRecordMapper;
    private final AuctionCacheService auctionCacheService;
    private final AuctionQualificationService auctionQualificationService;

    public MysqlBidEngine(AuctionRoomReadService auctionRoomReadService,
                          AuctionRoomMapper auctionRoomMapper,
                          AuctionBidRecordMapper auctionBidRecordMapper,
                          AuctionCacheService auctionCacheService,
                          AuctionQualificationService auctionQualificationService) {
        this.auctionRoomReadService = auctionRoomReadService;
        this.auctionRoomMapper = auctionRoomMapper;
        this.auctionBidRecordMapper = auctionBidRecordMapper;
        this.auctionCacheService = auctionCacheService;
        this.auctionQualificationService = auctionQualificationService;
    }

    @Override
    @Transactional
    public AuctionRoomSnapshot placeBid(String roomId, BidRequest request) {
        AuctionRoom room = auctionRoomReadService.findRoomForUpdate(roomId);
        validateRoomOpen(room, Instant.now());
        auctionQualificationService.assertEligibleToBid(room, request.userId());

        BigDecimal minNextBid = auctionRoomReadService.calculateMinNextBid(room);
        if (request.amount().compareTo(minNextBid) < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "bid must be greater than or equal to " + minNextBid
            );
        }

        Instant now = Instant.now();
        long nextVersion = room.getVersion() + 1;
        room.setCurrentPrice(request.amount());
        room.setLeaderUserId(request.userId());
        room.setLeaderNickname(request.nickname());
        room.setVersion(nextVersion);

        auctionBidRecordMapper.insert(new AuctionBidRecordEntity(
                room.getRoomId(),
                request.userId(),
                request.nickname(),
                request.amount(),
                nextVersion,
                now
        ));

        auctionRoomMapper.updateAfterBid(room);
        AuctionRoomSnapshot snapshot = auctionRoomReadService.toSnapshot(room, true);
        auctionCacheService.cacheRoom(snapshot);
        auctionCacheService.recordBid(roomId, request.userId(), request.nickname(), request.amount(), snapshot);
        return snapshot;
    }

    private void validateRoomOpen(AuctionRoom room, Instant now) {
        if (room.getStatus() == AuctionStatus.CLOSED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "auction already closed");
        }

        if (now.isAfter(room.getEndsAt())) {
            auctionRoomReadService.closeRoom(room);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "auction already closed");
        }
    }
}

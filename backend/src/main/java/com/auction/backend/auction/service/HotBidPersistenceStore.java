package com.auction.backend.auction.service;

import com.auction.backend.auction.mapper.AuctionBidRecordMapper;
import com.auction.backend.auction.mapper.AuctionRoomMapper;
import com.auction.backend.auction.model.AuctionBidRecordEntity;
import com.auction.backend.auction.model.AuctionRoom;
import com.auction.backend.auction.model.AuctionStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class HotBidPersistenceStore {

    private final AuctionBidRecordMapper auctionBidRecordMapper;
    private final AuctionRoomReadService auctionRoomReadService;
    private final AuctionRoomMapper auctionRoomMapper;
    private final AuctionWalletService auctionWalletService;

    public HotBidPersistenceStore(AuctionBidRecordMapper auctionBidRecordMapper,
                                  AuctionRoomReadService auctionRoomReadService,
                                  AuctionRoomMapper auctionRoomMapper,
                                  AuctionWalletService auctionWalletService) {
        this.auctionBidRecordMapper = auctionBidRecordMapper;
        this.auctionRoomReadService = auctionRoomReadService;
        this.auctionRoomMapper = auctionRoomMapper;
        this.auctionWalletService = auctionWalletService;
    }

    @Transactional
    public void persist(HotBidPersistenceMessage message) {
        if (auctionBidRecordMapper.findByEventId(message.eventId()) != null) {
            return;
        }

        auctionBidRecordMapper.insert(new AuctionBidRecordEntity(
                message.eventId(),
                message.roomId(),
                message.userId(),
                message.nickname(),
                message.amount(),
                message.roomVersion(),
                message.bidTime()
        ));

        auctionWalletService.applyHotBidReservation(
                message.userId(),
                message.amount(),
                message.previousLeaderUserId(),
                message.previousAmount()
        );

        AuctionRoom room = auctionRoomReadService.findRoom(message.roomId());
        room.setCurrentPrice(message.amount());
        room.setLeaderUserId(message.userId());
        room.setLeaderNickname(message.nickname());
        room.setEndsAt(message.endsAt());
        room.setStatus(message.endsAt().isAfter(Instant.now()) ? message.roomStatus() : AuctionStatus.CLOSED);
        room.setVersion(message.roomVersion());
        auctionRoomMapper.updateAfterBid(room);
    }
}

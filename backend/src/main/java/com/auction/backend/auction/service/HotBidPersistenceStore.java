package com.auction.backend.auction.service;

import com.auction.backend.auction.mapper.AuctionBidRecordMapper;
import com.auction.backend.auction.mapper.AuctionRoomMapper;
import com.auction.backend.auction.model.AuctionBidRecordEntity;
import com.auction.backend.auction.model.AuctionRoom;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HotBidPersistenceStore {

    private final AuctionBidRecordMapper auctionBidRecordMapper;
    private final AuctionRoomReadService auctionRoomReadService;
    private final AuctionRoomMapper auctionRoomMapper;

    public HotBidPersistenceStore(AuctionBidRecordMapper auctionBidRecordMapper,
                                  AuctionRoomReadService auctionRoomReadService,
                                  AuctionRoomMapper auctionRoomMapper) {
        this.auctionBidRecordMapper = auctionBidRecordMapper;
        this.auctionRoomReadService = auctionRoomReadService;
        this.auctionRoomMapper = auctionRoomMapper;
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
                message.bidTime()
        ));

        AuctionRoom room = auctionRoomReadService.findRoom(message.roomId());
        room.setCurrentPrice(message.amount());
        room.setLeaderUserId(message.userId());
        room.setLeaderNickname(message.nickname());
        room.setEndsAt(message.endsAt());
        auctionRoomMapper.updateAfterBid(room);
    }
}

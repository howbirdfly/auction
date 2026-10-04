package com.auction.backend.auction.service;

import com.auction.backend.auction.mapper.AuctionBidRecordMapper;
import com.auction.backend.auction.mapper.AuctionRoomMapper;
import com.auction.backend.auction.model.AuctionBidRecordEntity;
import com.auction.backend.auction.model.AuctionRoom;
import com.auction.backend.auction.model.AuctionStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        persistBatch(List.of(message));
    }

    @Transactional
    public void persistBatch(List<HotBidPersistenceMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        List<HotBidPersistenceMessage> newMessages = filterNewMessages(messages);
        if (newMessages.isEmpty()) {
            return;
        }

        auctionBidRecordMapper.insertBatch(newMessages.stream()
                .map(this::toBidRecord)
                .toList());

        Map<String, HotBidPersistenceMessage> latestRoomStates = new LinkedHashMap<>();
        for (HotBidPersistenceMessage message : newMessages) {
            auctionWalletService.applyHotBidReservation(
                    message.userId(),
                    message.amount(),
                    message.previousLeaderUserId(),
                    message.previousAmount(),
                    message.roomId(),
                    message.requestId()
            );
            latestRoomStates.merge(
                    message.roomId(),
                    message,
                    (current, candidate) -> candidate.roomVersion() >= current.roomVersion()
                            ? candidate
                            : current
            );
        }

        latestRoomStates.values().forEach(this::applyRoomState);
    }

    private List<HotBidPersistenceMessage> filterNewMessages(List<HotBidPersistenceMessage> messages) {
        List<HotBidPersistenceMessage> newMessages = new ArrayList<>();
        Set<String> eventIds = new HashSet<>();
        Set<String> requestIds = new HashSet<>();

        for (HotBidPersistenceMessage message : messages) {
            if (!eventIds.add(message.eventId()) || !requestIds.add(message.requestId())) {
                continue;
            }
            if (auctionBidRecordMapper.findByEventId(message.eventId()) != null
                    || auctionBidRecordMapper.findByRequestId(message.requestId()) != null) {
                continue;
            }
            newMessages.add(message);
        }
        return newMessages;
    }

    private AuctionBidRecordEntity toBidRecord(HotBidPersistenceMessage message) {
        return new AuctionBidRecordEntity(
                message.eventId(),
                message.requestId(),
                message.roomId(),
                message.userId(),
                message.nickname(),
                message.amount(),
                message.roomVersion(),
                message.bidTime()
        );
    }

    private void applyRoomState(HotBidPersistenceMessage message) {
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

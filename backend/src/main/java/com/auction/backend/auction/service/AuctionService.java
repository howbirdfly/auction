package com.auction.backend.auction.service;

import com.auction.backend.auction.dto.AuctionRoomSnapshot;
import com.auction.backend.auction.dto.BidRequest;
import com.auction.backend.auction.dto.CreateAuctionRequest;
import com.auction.backend.auction.model.AuctionRoom;
import com.auction.backend.auction.model.AuctionStatus;
import com.auction.backend.auction.model.BidRecord;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class AuctionService {

    private static final long LAST_SECOND_EXTENSION_WINDOW = 10;
    private static final long LAST_SECOND_EXTENSION_SECONDS = 15;
    private static final int MAX_RECENT_BIDS = 10;

    private final Map<String, AuctionRoom> rooms = new ConcurrentHashMap<>();
    private final AtomicLong roomSequence = new AtomicLong(1000);
    private final AuctionBroadcastService broadcastService;

    public AuctionService(AuctionBroadcastService broadcastService) {
        this.broadcastService = broadcastService;
        seedDemoRoom();
    }

    public List<AuctionRoomSnapshot> listRooms() {
        return rooms.values().stream()
                .sorted(Comparator.comparing(AuctionRoom::getEndsAt))
                .map(this::toSnapshot)
                .toList();
    }

    public AuctionRoomSnapshot getRoom(String roomId) {
        return toSnapshot(findRoom(roomId));
    }

    public AuctionRoomSnapshot createRoom(CreateAuctionRequest request) {
        String roomId = "AR-" + roomSequence.incrementAndGet();
        AuctionRoom room = new AuctionRoom(
                roomId,
                request.itemTitle(),
                request.anchorName(),
                request.startPrice(),
                request.stepPrice(),
                Instant.now().plusSeconds(request.durationSeconds()),
                AuctionStatus.BIDDING
        );
        rooms.put(roomId, room);
        AuctionRoomSnapshot snapshot = toSnapshot(room);
        broadcastService.broadcastLobby(listRooms());
        broadcastService.broadcastRoom(snapshot);
        return snapshot;
    }

    public AuctionRoomSnapshot placeBid(String roomId, BidRequest request) {
        AuctionRoom room = findRoom(roomId);

        synchronized (room) {
            if (room.getStatus() == AuctionStatus.CLOSED) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "auction already closed");
            }

            if (Instant.now().isAfter(room.getEndsAt())) {
                closeRoom(room);
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "auction already closed");
            }

            BigDecimal minNextBid = calculateMinNextBid(room);
            if (request.amount().compareTo(minNextBid) < 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "bid must be greater than or equal to " + minNextBid
                );
            }

            room.setCurrentPrice(request.amount());
            room.setLeaderUserId(request.userId());
            room.setLeaderNickname(request.nickname());
            room.getBidRecords().addFirst(new BidRecord(
                    request.userId(),
                    request.nickname(),
                    request.amount(),
                    Instant.now()
            ));

            if (room.getBidRecords().size() > MAX_RECENT_BIDS) {
                room.getBidRecords().removeLast();
            }

            long secondsRemaining = Duration.between(Instant.now(), room.getEndsAt()).toSeconds();
            if (secondsRemaining <= LAST_SECOND_EXTENSION_WINDOW) {
                room.setEndsAt(room.getEndsAt().plusSeconds(LAST_SECOND_EXTENSION_SECONDS));
            }
        }

        AuctionRoomSnapshot snapshot = toSnapshot(room);
        broadcastService.broadcastRoom(snapshot);
        broadcastService.broadcastLobby(listRooms());
        return snapshot;
    }

    @Scheduled(fixedDelay = 1000)
    public void closeExpiredRooms() {
        rooms.values().forEach(room -> {
            synchronized (room) {
                if (room.getStatus() == AuctionStatus.BIDDING && Instant.now().isAfter(room.getEndsAt())) {
                    closeRoom(room);
                }
            }
        });
    }

    private void closeRoom(AuctionRoom room) {
        room.setStatus(AuctionStatus.CLOSED);
        AuctionRoomSnapshot snapshot = toSnapshot(room);
        broadcastService.broadcastRoom(snapshot);
        broadcastService.broadcastLobby(listRooms());
    }

    private AuctionRoom findRoom(String roomId) {
        AuctionRoom room = rooms.get(roomId);
        if (room == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "auction room not found");
        }
        return room;
    }

    private AuctionRoomSnapshot toSnapshot(AuctionRoom room) {
        long secondsRemaining = room.getStatus() == AuctionStatus.CLOSED
                ? 0
                : Math.max(0, Duration.between(Instant.now(), room.getEndsAt()).toSeconds());

        return new AuctionRoomSnapshot(
                room.getRoomId(),
                room.getItemTitle(),
                room.getAnchorName(),
                room.getStatus(),
                room.getStartPrice(),
                room.getCurrentPrice(),
                room.getStepPrice(),
                calculateMinNextBid(room),
                room.getLeaderNickname(),
                room.getEndsAt(),
                secondsRemaining,
                room.getBidRecords().size(),
                List.copyOf(room.getBidRecords())
        );
    }

    private BigDecimal calculateMinNextBid(AuctionRoom room) {
        if (!room.hasLeader()) {
            return room.getStartPrice();
        }
        return room.getCurrentPrice().add(room.getStepPrice());
    }

    private void seedDemoRoom() {
        String roomId = "AR-" + roomSequence.incrementAndGet();
        AuctionRoom room = new AuctionRoom(
                roomId,
                "主播限量球鞋盲盒",
                "直播主理人Ava",
                BigDecimal.valueOf(199),
                BigDecimal.valueOf(20),
                Instant.now().plusSeconds(600),
                AuctionStatus.BIDDING
        );
        rooms.put(roomId, room);
    }
}

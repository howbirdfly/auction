package com.auction.backend.auction.service;

import com.auction.backend.auction.cache.AuctionCacheService;
import com.auction.backend.auction.dto.AuctionLeaderboardEntry;
import com.auction.backend.auction.dto.AuctionRoomSnapshot;
import com.auction.backend.auction.mapper.AuctionBidRecordMapper;
import com.auction.backend.auction.mapper.AuctionRoomMapper;
import com.auction.backend.auction.model.AuctionBidRecordEntity;
import com.auction.backend.auction.model.AuctionLeaderboardRow;
import com.auction.backend.auction.model.AuctionRoom;
import com.auction.backend.auction.model.AuctionStatus;
import com.auction.backend.auction.model.BidRecord;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

@Service
public class AuctionRoomReadService {

    private static final int RECENT_BID_LIMIT = 10;
    private static final int LEADERBOARD_LIMIT = 10;

    private final AuctionRoomMapper auctionRoomMapper;
    private final AuctionBidRecordMapper auctionBidRecordMapper;
    private final AuctionCacheService auctionCacheService;

    public AuctionRoomReadService(AuctionRoomMapper auctionRoomMapper,
                                  AuctionBidRecordMapper auctionBidRecordMapper,
                                  AuctionCacheService auctionCacheService) {
        this.auctionRoomMapper = auctionRoomMapper;
        this.auctionBidRecordMapper = auctionBidRecordMapper;
        this.auctionCacheService = auctionCacheService;
    }

    @Transactional(readOnly = true)
    public List<AuctionRoomSnapshot> listRooms() {
        return auctionCacheService.getLobby()
                .orElseGet(this::loadLobbySnapshots);
    }

    @Transactional(readOnly = true)
    public AuctionRoomSnapshot getRoom(String roomId) {
        return auctionCacheService.getRoom(roomId)
                .orElseGet(() -> {
                    AuctionRoomSnapshot snapshot = toSnapshot(findRoom(roomId), true);
                    auctionCacheService.cacheRoom(snapshot);
                    return snapshot;
                });
    }

    @Transactional(readOnly = true)
    public List<AuctionLeaderboardEntry> getLeaderboard(String roomId) {
        findRoom(roomId);

        return auctionCacheService.getLeaderboard(roomId)
                .orElseGet(() -> {
                    List<AuctionLeaderboardEntry> leaderboard = loadLeaderboard(roomId);
                    auctionCacheService.cacheLeaderboard(roomId, leaderboard, getRoom(roomId));
                    return leaderboard;
                });
    }

    public AuctionRoom findRoom(String roomId) {
        AuctionRoom room = auctionRoomMapper.findById(roomId);
        if (room == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "auction room not found");
        }
        return room;
    }

    public AuctionRoom findRoomForUpdate(String roomId) {
        AuctionRoom room = auctionRoomMapper.findByIdForUpdate(roomId);
        if (room == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "auction room not found");
        }
        return room;
    }

    public AuctionRoomSnapshot toSnapshot(AuctionRoom room, boolean includeRecentBids) {
        long secondsRemaining = room.getStatus() == AuctionStatus.CLOSED
                ? 0
                : Math.max(0, Duration.between(Instant.now(), room.getEndsAt()).toSeconds());

        List<BidRecord> recentBids = includeRecentBids
                ? auctionBidRecordMapper.findLatestByRoomId(room.getRoomId(), RECENT_BID_LIMIT).stream()
                .map(this::toBidRecord)
                .toList()
                : List.of();

        return new AuctionRoomSnapshot(
                room.getRoomId(),
                room.getItemTitle(),
                room.getAnchorName(),
                room.getImageUrl(),
                room.getStatus(),
                room.getStartPrice(),
                room.getCurrentPrice(),
                room.getStepPrice(),
                calculateMinNextBid(room),
                room.getLeaderNickname(),
                room.getEndsAt(),
                secondsRemaining,
                Math.toIntExact(auctionBidRecordMapper.countByRoomId(room.getRoomId())),
                recentBids
        );
    }

    public List<AuctionRoomSnapshot> refreshLobbyCache() {
        auctionCacheService.evictLobby();
        return loadLobbySnapshots();
    }

    public List<AuctionLeaderboardEntry> loadLeaderboard(String roomId) {
        List<AuctionLeaderboardRow> rows = auctionBidRecordMapper.findLeaderboardByRoomId(roomId, LEADERBOARD_LIMIT);
        return IntStream.range(0, rows.size())
                .mapToObj(index -> {
                    AuctionLeaderboardRow row = rows.get(index);
                    return new AuctionLeaderboardEntry(
                            index + 1,
                            row.getUserId(),
                            row.getNickname(),
                            row.getAmount()
                    );
                })
                .toList();
    }

    public BigDecimal calculateMinNextBid(AuctionRoom room) {
        if (!room.hasLeader()) {
            return room.getStartPrice();
        }
        return room.getCurrentPrice().add(room.getStepPrice());
    }

    public BigDecimal calculateMinNextBid(AuctionRoomSnapshot room) {
        if (room.leaderNickname() == null || room.leaderNickname().isBlank()) {
            return room.startPrice();
        }
        return room.currentPrice().add(room.stepPrice());
    }

    public void closeRoom(AuctionRoom room) {
        room.setStatus(AuctionStatus.CLOSED);
        auctionRoomMapper.updateStatus(room.getRoomId(), AuctionStatus.CLOSED);
    }

    private List<AuctionRoomSnapshot> loadLobbySnapshots() {
        List<AuctionRoomSnapshot> snapshots = auctionRoomMapper.findAllOrderByEndsAtAsc().stream()
                .map(room -> toSnapshot(room, false))
                .toList();
        auctionCacheService.cacheLobby(snapshots);
        return snapshots;
    }

    private BidRecord toBidRecord(AuctionBidRecordEntity entity) {
        return new BidRecord(
                entity.getUserId(),
                entity.getNickname(),
                entity.getAmount(),
                entity.getBidTime()
        );
    }
}

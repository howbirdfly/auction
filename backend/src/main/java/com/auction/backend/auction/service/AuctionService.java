package com.auction.backend.auction.service;

import com.auction.backend.auction.cache.AuctionCacheService;
import com.auction.backend.auction.config.AuctionCacheProperties;
import com.auction.backend.auction.dto.AuctionLeaderboardEntry;
import com.auction.backend.auction.dto.AuctionRoomSnapshot;
import com.auction.backend.auction.dto.BidRequest;
import com.auction.backend.auction.dto.CreateAuctionRequest;
import com.auction.backend.auction.model.AuctionLeaderboardRow;
import com.auction.backend.auction.mapper.AuctionBidRecordMapper;
import com.auction.backend.auction.mapper.AuctionRoomMapper;
import com.auction.backend.auction.model.AuctionBidRecordEntity;
import com.auction.backend.auction.model.AuctionRoom;
import com.auction.backend.auction.model.AuctionStatus;
import com.auction.backend.auction.model.BidRecord;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class AuctionService {

    private static final long LAST_SECOND_EXTENSION_WINDOW = 10;
    private static final long LAST_SECOND_EXTENSION_SECONDS = 15;
    private static final int RECENT_BID_LIMIT = 10;
    private static final int LEADERBOARD_LIMIT = 10;
    private static final String DEFAULT_COVER_URL = "https://placehold.co/800x600/f6f7fb/1f2937?text=Auction+Room";

    private final AtomicLong roomSequence = new AtomicLong(1000);
    private final AuctionRoomMapper auctionRoomMapper;
    private final AuctionBidRecordMapper auctionBidRecordMapper;
    private final AuctionBroadcastService broadcastService;
    private final AuctionCacheService auctionCacheService;
    private final AuctionCacheProperties auctionCacheProperties;

    public AuctionService(AuctionRoomMapper auctionRoomMapper,
                          AuctionBidRecordMapper auctionBidRecordMapper,
                          AuctionBroadcastService broadcastService,
                          AuctionCacheService auctionCacheService,
                          AuctionCacheProperties auctionCacheProperties) {
        this.auctionRoomMapper = auctionRoomMapper;
        this.auctionBidRecordMapper = auctionBidRecordMapper;
        this.broadcastService = broadcastService;
        this.auctionCacheService = auctionCacheService;
        this.auctionCacheProperties = auctionCacheProperties;
    }

    @PostConstruct
    public void initializeData() {
        roomSequence.set(resolveCurrentSequence());
        if (auctionRoomMapper.countRooms() == 0) {
            seedDemoRooms();
        }
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

    @Transactional
    public AuctionRoomSnapshot createRoom(CreateAuctionRequest request) {
        String roomId = "AR-" + roomSequence.incrementAndGet();
        AuctionRoom room = new AuctionRoom(
                roomId,
                request.itemTitle(),
                request.anchorName(),
                resolveImageUrl(request.imageUrl()),
                request.startPrice(),
                request.stepPrice(),
                Instant.now().plusSeconds(request.durationSeconds()),
                AuctionStatus.BIDDING
        );
        auctionRoomMapper.insert(room);

        AuctionRoomSnapshot snapshot = toSnapshot(room, true);
        auctionCacheService.cacheRoom(snapshot);
        List<AuctionRoomSnapshot> lobby = refreshLobbyCache();
        broadcastService.broadcastLobby(lobby);
        broadcastService.broadcastRoom(snapshot);
        return snapshot;
    }

    @Transactional
    public void deleteExpiredRoom(String roomId) {
        AuctionRoom room = findRoom(roomId);
        if (room.getStatus() != AuctionStatus.CLOSED && Instant.now().isBefore(room.getEndsAt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "only closed auction rooms can be deleted");
        }

        auctionBidRecordMapper.deleteByRoomId(roomId);
        auctionRoomMapper.deleteById(roomId);
        auctionCacheService.evictRoom(roomId);
        auctionCacheService.evictLeaderboard(roomId);
        broadcastService.broadcastLobby(refreshLobbyCache());
    }

    @Transactional
    public AuctionRoomSnapshot placeBid(String roomId, BidRequest request) {
        AuctionRoom room = auctionRoomMapper.findByIdForUpdate(roomId);
        if (room == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "auction room not found");
        }

        if (room.getStatus() == AuctionStatus.CLOSED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "auction already closed");
        }

        Instant now = Instant.now();
        if (now.isAfter(room.getEndsAt())) {
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

        auctionBidRecordMapper.insert(new AuctionBidRecordEntity(
                room.getRoomId(),
                request.userId(),
                request.nickname(),
                request.amount(),
                now
        ));

        long secondsRemaining = Duration.between(now, room.getEndsAt()).toSeconds();
        if (secondsRemaining <= LAST_SECOND_EXTENSION_WINDOW) {
            room.setEndsAt(room.getEndsAt().plusSeconds(LAST_SECOND_EXTENSION_SECONDS));
        }

        auctionRoomMapper.updateAfterBid(room);
        AuctionRoomSnapshot snapshot = toSnapshot(room, true);
        auctionCacheService.cacheRoom(snapshot);
        auctionCacheService.recordBid(roomId, request.userId(), request.nickname(), request.amount(), snapshot);
        broadcastService.broadcastRoom(snapshot);
        broadcastService.broadcastLobby(refreshLobbyCache());
        return snapshot;
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void closeExpiredRooms() {
        List<AuctionRoom> expiredRooms = auctionRoomMapper.findExpiredRooms(
                AuctionStatus.BIDDING,
                Instant.now()
        );

        if (expiredRooms.isEmpty()) {
            return;
        }

        expiredRooms.forEach(this::closeRoom);
        List<AuctionRoomSnapshot> lobby = refreshLobbyCache();
        broadcastService.broadcastLobby(lobby);
        expiredRooms.forEach(room -> {
            AuctionRoomSnapshot snapshot = toSnapshot(room, true);
            auctionCacheService.cacheRoom(snapshot);
            auctionCacheService.cacheLeaderboard(room.getRoomId(), loadLeaderboard(room.getRoomId()), snapshot);
            broadcastService.broadcastRoom(snapshot);
        });
    }

    @Scheduled(fixedDelay = 15000)
    @Transactional(readOnly = true)
    public void warmHotRoomCache() {
        List<AuctionRoom> hotRooms = auctionRoomMapper.findAllOrderByEndsAtAsc().stream()
                .filter(this::isHotRoom)
                .toList();

        hotRooms.forEach(room -> {
            AuctionRoomSnapshot snapshot = toSnapshot(room, true);
            auctionCacheService.cacheRoom(snapshot);
            auctionCacheService.cacheLeaderboard(room.getRoomId(), loadLeaderboard(room.getRoomId()), snapshot);
        });
    }

    private void closeRoom(AuctionRoom room) {
        room.setStatus(AuctionStatus.CLOSED);
        auctionRoomMapper.updateStatus(room.getRoomId(), AuctionStatus.CLOSED);
    }

    private AuctionRoom findRoom(String roomId) {
        AuctionRoom room = auctionRoomMapper.findById(roomId);
        if (room == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "auction room not found");
        }
        return room;
    }

    private AuctionRoomSnapshot toSnapshot(AuctionRoom room, boolean includeRecentBids) {
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

    private BidRecord toBidRecord(AuctionBidRecordEntity entity) {
        return new BidRecord(
                entity.getUserId(),
                entity.getNickname(),
                entity.getAmount(),
                entity.getBidTime()
        );
    }

    private BigDecimal calculateMinNextBid(AuctionRoom room) {
        if (!room.hasLeader()) {
            return room.getStartPrice();
        }
        return room.getCurrentPrice().add(room.getStepPrice());
    }

    private String resolveImageUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return DEFAULT_COVER_URL;
        }
        return imageUrl.trim();
    }

    private long resolveCurrentSequence() {
        return auctionRoomMapper.findAllRoomIds().stream()
                .map(id -> id.replace("AR-", ""))
                .mapToLong(id -> {
                    try {
                        return Long.parseLong(id);
                    } catch (NumberFormatException ignored) {
                        return 1000L;
                    }
                })
                .max()
                .orElse(1000L);
    }

    private List<AuctionRoomSnapshot> loadLobbySnapshots() {
        List<AuctionRoomSnapshot> snapshots = auctionRoomMapper.findAllOrderByEndsAtAsc().stream()
                .map(room -> toSnapshot(room, false))
                .toList();
        auctionCacheService.cacheLobby(snapshots);
        return snapshots;
    }

    private List<AuctionRoomSnapshot> refreshLobbyCache() {
        auctionCacheService.evictLobby();
        return loadLobbySnapshots();
    }

    private List<AuctionLeaderboardEntry> loadLeaderboard(String roomId) {
        List<AuctionLeaderboardRow> rows = auctionBidRecordMapper.findLeaderboardByRoomId(roomId, LEADERBOARD_LIMIT);
        return java.util.stream.IntStream.range(0, rows.size())
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

    private boolean isHotRoom(AuctionRoom room) {
        if (room.getStatus() != AuctionStatus.BIDDING) {
            return false;
        }

        long secondsRemaining = Duration.between(Instant.now(), room.getEndsAt()).toSeconds();
        return secondsRemaining > 0 && secondsRemaining <= auctionCacheProperties.getHotRoomWindow().toSeconds();
    }

    private void seedDemoRooms() {
        addSeedRoom(
                "Anchor sneaker blind box",
                "Host Ava",
                "https://placehold.co/800x600/f7efe2/20242c?text=Sneaker+Blind+Box",
                199,
                20,
                600
        );
        addSeedRoom(
                "Collectible figure night",
                "Host Miko",
                "https://placehold.co/800x600/eaf3ff/1e293b?text=Collectible+Toys",
                299,
                30,
                720
        );
        addSeedRoom(
                "Designer bag sale room",
                "Host Coco",
                "https://placehold.co/800x600/fff5e8/312e2b?text=Designer+Bag",
                599,
                50,
                840
        );
        addSeedRoom(
                "Digital headphone late show",
                "Host Neo",
                "https://placehold.co/800x600/edf7f6/1f2937?text=Digital+Headphone",
                399,
                25,
                960
        );
    }

    private void addSeedRoom(String itemTitle,
                             String anchorName,
                             String imageUrl,
                             int startPrice,
                             int stepPrice,
                             int durationSeconds) {
        String roomId = "AR-" + roomSequence.incrementAndGet();
        auctionRoomMapper.insert(new AuctionRoom(
                roomId,
                itemTitle,
                anchorName,
                imageUrl,
                BigDecimal.valueOf(startPrice),
                BigDecimal.valueOf(stepPrice),
                Instant.now().plusSeconds(durationSeconds),
                AuctionStatus.BIDDING
        ));
    }
}

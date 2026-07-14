package com.auction.backend.auction.service;

import com.auction.backend.auction.config.AuctionCacheProperties;
import com.auction.backend.auction.dto.AuctionLeaderboardEntry;
import com.auction.backend.auction.dto.AuctionRoomSnapshot;
import com.auction.backend.auction.dto.BidRequest;
import com.auction.backend.auction.dto.CreateAuctionRequest;
import com.auction.backend.auction.mapper.AuctionBidRecordMapper;
import com.auction.backend.auction.mapper.AuctionRoomMapper;
import com.auction.backend.auction.model.AuctionRoom;
import com.auction.backend.auction.model.AuctionStatus;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class AuctionService {

    private static final String DEFAULT_COVER_URL = "https://placehold.co/800x600/f6f7fb/1f2937?text=Auction+Room";

    private final AtomicLong roomSequence = new AtomicLong(1000);
    private final AuctionRoomMapper auctionRoomMapper;
    private final AuctionBidRecordMapper auctionBidRecordMapper;
    private final AuctionBroadcastService broadcastService;
    private final AuctionRoomReadService auctionRoomReadService;
    private final BidEngineRouter bidEngineRouter;
    private final HotRoomManager hotRoomManager;
    private final AuctionCacheProperties auctionCacheProperties;

    public AuctionService(AuctionRoomMapper auctionRoomMapper,
                          AuctionBidRecordMapper auctionBidRecordMapper,
                          AuctionBroadcastService broadcastService,
                          AuctionRoomReadService auctionRoomReadService,
                          BidEngineRouter bidEngineRouter,
                          HotRoomManager hotRoomManager,
                          AuctionCacheProperties auctionCacheProperties) {
        this.auctionRoomMapper = auctionRoomMapper;
        this.auctionBidRecordMapper = auctionBidRecordMapper;
        this.broadcastService = broadcastService;
        this.auctionRoomReadService = auctionRoomReadService;
        this.bidEngineRouter = bidEngineRouter;
        this.hotRoomManager = hotRoomManager;
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
        return auctionRoomReadService.listRooms();
    }

    @Transactional(readOnly = true)
    public AuctionRoomSnapshot getRoom(String roomId) {
        AuctionRoomSnapshot snapshot = auctionRoomReadService.getRoom(roomId);

        if (shouldPromoteToHot(snapshot) && hotRoomManager.recordAccess(roomId)) {
            hotRoomManager.markHot(snapshot, auctionRoomReadService.loadLeaderboard(roomId));
        }

        return snapshot;
    }

    @Transactional(readOnly = true)
    public List<AuctionLeaderboardEntry> getLeaderboard(String roomId) {
        return auctionRoomReadService.getLeaderboard(roomId);
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

        AuctionRoomSnapshot snapshot = auctionRoomReadService.toSnapshot(room, true);
        List<AuctionRoomSnapshot> lobby = auctionRoomReadService.refreshLobbyCache();
        broadcastService.broadcastLobby(lobby);
        broadcastService.broadcastRoom(snapshot);
        return snapshot;
    }

    @Transactional
    public void deleteExpiredRoom(String roomId) {
        AuctionRoom room = auctionRoomReadService.findRoom(roomId);
        if (room.getStatus() != AuctionStatus.CLOSED && Instant.now().isBefore(room.getEndsAt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "only closed auction rooms can be deleted");
        }

        auctionBidRecordMapper.deleteByRoomId(roomId);
        auctionRoomMapper.deleteById(roomId);
        auctionRoomReadService.evictRoomState(roomId);
        hotRoomManager.clear(roomId);
        broadcastService.broadcastLobby(auctionRoomReadService.refreshLobbyCache());
    }

    @Transactional
    public AuctionRoomSnapshot placeBid(String roomId, BidRequest request) {
        AuctionRoomSnapshot snapshot = bidEngineRouter.placeBid(roomId, request);
        if (hotRoomManager.isHot(roomId)) {
            hotRoomManager.markHot(snapshot, auctionRoomReadService.loadLeaderboard(roomId));
        }

        broadcastService.broadcastRoom(snapshot);
        broadcastService.broadcastLobby(auctionRoomReadService.refreshLobbyCache());
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

        expiredRooms.forEach(room -> {
            auctionRoomReadService.closeRoom(room);
            hotRoomManager.clear(room.getRoomId());
        });

        List<AuctionRoomSnapshot> lobby = auctionRoomReadService.refreshLobbyCache();
        broadcastService.broadcastLobby(lobby);
        expiredRooms.forEach(room -> {
            AuctionRoomSnapshot snapshot = auctionRoomReadService.toSnapshot(room, true);
            broadcastService.broadcastRoom(snapshot);
        });
    }

    @Scheduled(fixedDelay = 15000)
    @Transactional(readOnly = true)
    public void warmHotRoomCache() {
        List<AuctionRoom> rooms = auctionRoomMapper.findAllOrderByEndsAtAsc().stream()
                .filter(room -> room.getStatus() == AuctionStatus.BIDDING)
                .toList();

        rooms.forEach(room -> {
            if (!hotRoomManager.isHot(room.getRoomId())) {
                return;
            }

            AuctionRoomSnapshot snapshot = auctionRoomReadService.toSnapshot(room, true);
            hotRoomManager.markHot(snapshot, auctionRoomReadService.loadLeaderboard(room.getRoomId()));
        });
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

    private boolean shouldPromoteToHot(AuctionRoomSnapshot snapshot) {
        if (snapshot.status() != AuctionStatus.BIDDING || hotRoomManager.isHot(snapshot.roomId())) {
            return false;
        }

        Instant hotWindowStart = snapshot.endsAt().minus(auctionCacheProperties.getHotRoomWindow());
        return !Instant.now().isBefore(hotWindowStart);
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

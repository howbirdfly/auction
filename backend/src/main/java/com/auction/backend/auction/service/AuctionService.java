package com.auction.backend.auction.service;

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
    private final BidRequestIdempotencyService bidRequestIdempotencyService;
    private final HotRoomManager hotRoomManager;
    private final AuctionQualificationService auctionQualificationService;
    private final AuctionWalletService auctionWalletService;
    private final AuctionSettlementService auctionSettlementService;

    public AuctionService(AuctionRoomMapper auctionRoomMapper,
                          AuctionBidRecordMapper auctionBidRecordMapper,
                          AuctionBroadcastService broadcastService,
                          AuctionRoomReadService auctionRoomReadService,
                          BidEngineRouter bidEngineRouter,
                          BidRequestIdempotencyService bidRequestIdempotencyService,
                          HotRoomManager hotRoomManager,
                          AuctionQualificationService auctionQualificationService,
                          AuctionWalletService auctionWalletService,
                          AuctionSettlementService auctionSettlementService) {
        this.auctionRoomMapper = auctionRoomMapper;
        this.auctionBidRecordMapper = auctionBidRecordMapper;
        this.broadcastService = broadcastService;
        this.auctionRoomReadService = auctionRoomReadService;
        this.bidEngineRouter = bidEngineRouter;
        this.bidRequestIdempotencyService = bidRequestIdempotencyService;
        this.hotRoomManager = hotRoomManager;
        this.auctionQualificationService = auctionQualificationService;
        this.auctionWalletService = auctionWalletService;
        this.auctionSettlementService = auctionSettlementService;
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

        if (snapshot.status() == AuctionStatus.BIDDING
                && snapshot.secondsRemaining() > 0
                && !hotRoomManager.isHot(roomId)
                && hotRoomManager.recordAccess(roomId)) {
            hotRoomManager.markHot(snapshot, auctionRoomReadService.loadLeaderboard(roomId));
            return auctionRoomReadService.getRoom(roomId);
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
        boolean registrationRequired = auctionQualificationService.resolveRegistrationRequired(request.registrationRequired());
        AuctionRoom room = new AuctionRoom(
                roomId,
                request.itemTitle(),
                request.anchorName(),
                resolveImageUrl(request.imageUrl()),
                request.startPrice(),
                request.stepPrice(),
                registrationRequired,
                auctionQualificationService.resolveDepositAmount(request.depositAmount(), registrationRequired),
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
        BidRequestIdempotencyService.BidRequestDecision decision = bidRequestIdempotencyService.begin(roomId, request);
        if (!decision.accepted()) {
            return auctionRoomReadService.getRoom(roomId);
        }

        AuctionRoomSnapshot snapshot;
        try {
            snapshot = bidEngineRouter.placeBid(roomId, request);
            bidRequestIdempotencyService.markSuccess(request.requestId(), snapshot.version());
        } catch (ResponseStatusException exception) {
            bidRequestIdempotencyService.markFailed(request.requestId(), exception.getReason());
            throw exception;
        }

        if (hotRoomManager.isHot(roomId)) {
            List<AuctionLeaderboardEntry> leaderboard = auctionRoomReadService.getLeaderboard(roomId);
            hotRoomManager.markHot(snapshot, leaderboard);
            broadcastService.broadcastLeaderboard(snapshot, leaderboard);
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
            auctionSettlementService.settle(room);
            hotRoomManager.clear(room.getRoomId());
        });

        List<AuctionRoomSnapshot> lobby = auctionRoomReadService.refreshLobbyCache();
        broadcastService.broadcastLobby(lobby);
        expiredRooms.forEach(room -> {
            AuctionRoomSnapshot snapshot = auctionRoomReadService.toSnapshot(room, true);
            broadcastService.broadcastRoom(snapshot);
            broadcastService.broadcastLeaderboard(snapshot, auctionRoomReadService.getLeaderboard(room.getRoomId()));
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
            hotRoomManager.markHot(snapshot, auctionRoomReadService.getLeaderboard(room.getRoomId()));
        });
    }

    @Scheduled(fixedDelay = 15000)
    public void compensateSettlements() {
        auctionSettlementService.compensate();
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

    private void seedDemoRooms() {
        addSeedRoom(
                "Anchor sneaker blind box",
                "Host Ava",
                "https://placehold.co/800x600/f7efe2/20242c?text=Sneaker+Blind+Box",
                199,
                20,
                true,
                99,
                600
        );
        addSeedRoom(
                "Collectible figure night",
                "Host Miko",
                "https://placehold.co/800x600/eaf3ff/1e293b?text=Collectible+Toys",
                299,
                30,
                true,
                99,
                720
        );
        addSeedRoom(
                "Designer bag sale room",
                "Host Coco",
                "https://placehold.co/800x600/fff5e8/312e2b?text=Designer+Bag",
                599,
                50,
                true,
                199,
                840
        );
        addSeedRoom(
                "Digital headphone late show",
                "Host Neo",
                "https://placehold.co/800x600/edf7f6/1f2937?text=Digital+Headphone",
                399,
                25,
                true,
                99,
                960
        );
    }

    private void addSeedRoom(String itemTitle,
                             String anchorName,
                             String imageUrl,
                             int startPrice,
                             int stepPrice,
                             boolean registrationRequired,
                             int depositAmount,
                             int durationSeconds) {
        String roomId = "AR-" + roomSequence.incrementAndGet();
        auctionRoomMapper.insert(new AuctionRoom(
                roomId,
                itemTitle,
                anchorName,
                imageUrl,
                BigDecimal.valueOf(startPrice),
                BigDecimal.valueOf(stepPrice),
                registrationRequired,
                BigDecimal.valueOf(depositAmount),
                Instant.now().plusSeconds(durationSeconds),
                AuctionStatus.BIDDING
        ));
    }
}

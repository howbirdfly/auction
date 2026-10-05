package com.auction.backend.auction.controller;

import com.auction.backend.auction.dto.EngineModeStatus;
import com.auction.backend.auction.dto.HotRoomRecoveryResult;
import com.auction.backend.auction.dto.HotBidReplayResult;
import com.auction.backend.auction.dto.AuctionRoomSnapshot;
import com.auction.backend.auction.mapper.AuctionRoomMapper;
import com.auction.backend.auction.model.AuctionRoom;
import com.auction.backend.auction.service.AuctionRoomReadService;
import com.auction.backend.auction.service.HotBidStreamStatus;
import com.auction.backend.auction.service.HotBidPersistenceCompensationService;
import com.auction.backend.auction.service.HotRoomManager;
import com.auction.backend.auction.service.HotRoomStatus;
import com.auction.backend.auction.service.RedisHotBidStreamPersistenceService;
import com.auction.backend.common.ApiResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/auction-persistence")
public class AuctionPersistenceAdminController {

    private final HotBidPersistenceCompensationService hotBidPersistenceCompensationService;
    private final ObjectProvider<RedisHotBidStreamPersistenceService> streamPersistenceServiceProvider;
    private final AuctionRoomMapper auctionRoomMapper;
    private final AuctionRoomReadService auctionRoomReadService;
    private final HotRoomManager hotRoomManager;

    public AuctionPersistenceAdminController(HotBidPersistenceCompensationService hotBidPersistenceCompensationService,
                                             ObjectProvider<RedisHotBidStreamPersistenceService> streamPersistenceServiceProvider,
                                             AuctionRoomMapper auctionRoomMapper,
                                             AuctionRoomReadService auctionRoomReadService,
                                             HotRoomManager hotRoomManager) {
        this.hotBidPersistenceCompensationService = hotBidPersistenceCompensationService;
        this.streamPersistenceServiceProvider = streamPersistenceServiceProvider;
        this.auctionRoomMapper = auctionRoomMapper;
        this.auctionRoomReadService = auctionRoomReadService;
        this.hotRoomManager = hotRoomManager;
    }

    @GetMapping("/stream/status")
    public ApiResponse<HotBidStreamStatus> streamStatus() {
        RedisHotBidStreamPersistenceService streamPersistenceService =
                streamPersistenceServiceProvider.getIfAvailable();
        if (streamPersistenceService == null) {
            return ApiResponse.success(
                    "redis stream persistence is disabled",
                    new HotBidStreamStatus(
                            "disabled",
                            "disabled",
                            null,
                            0L,
                            0L,
                            0L,
                            0L,
                            false
                    )
            );
        }
        return ApiResponse.success(streamPersistenceService.status());
    }

    @GetMapping("/rooms/{roomId}/engine-mode")
    public ApiResponse<EngineModeStatus> engineMode(@PathVariable String roomId) {
        AuctionRoom room = auctionRoomMapper.findById(roomId);
        if (room == null) {
            return ApiResponse.failure("auction room not found");
        }
        return ApiResponse.success(new EngineModeStatus(
                room.getRoomId(),
                room.getEngineMode(),
                room.getVersion()
        ));
    }

    @PostMapping("/rooms/{roomId}/rebuild-hot-state")
    public ApiResponse<HotRoomRecoveryResult> rebuildHotState(@PathVariable String roomId) {
        AuctionRoom room = auctionRoomReadService.findRoom(roomId);
        AuctionRoomSnapshot snapshot = auctionRoomReadService.toSnapshot(room, true);
        hotRoomManager.markHot(snapshot, auctionRoomReadService.loadLeaderboard(roomId));
        AuctionRoom refreshedRoom = auctionRoomReadService.findRoom(roomId);
        HotRoomStatus status = hotRoomManager.status(roomId);
        if (status != HotRoomStatus.HOT) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "failed to rebuild hot room state"
            );
        }
        return ApiResponse.success("hot room state rebuilt from MySQL", new HotRoomRecoveryResult(
                roomId,
                refreshedRoom.getEngineMode(),
                status,
                refreshedRoom.getVersion()
        ));
    }

    @PostMapping("/replay")
    public ApiResponse<HotBidReplayResult> replayPending() {
        return ApiResponse.success(
                "hot bid persistence pending events replayed",
                hotBidPersistenceCompensationService.replayPending()
        );
    }

    @PostMapping("/events/{eventId}/replay")
    public ApiResponse<HotBidReplayResult> replayEvent(@PathVariable String eventId) {
        return ApiResponse.success(
                "hot bid persistence event replayed",
                hotBidPersistenceCompensationService.replayEvent(eventId)
        );
    }

    @PostMapping("/rooms/{roomId}/replay")
    public ApiResponse<HotBidReplayResult> replayRoom(@PathVariable String roomId) {
        return ApiResponse.success(
                "hot bid persistence room replayed",
                hotBidPersistenceCompensationService.replayRoom(roomId)
        );
    }
}

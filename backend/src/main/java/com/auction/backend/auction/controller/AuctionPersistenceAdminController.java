package com.auction.backend.auction.controller;

import com.auction.backend.auction.dto.HotBidReplayResult;
import com.auction.backend.auction.service.HotBidPersistenceCompensationService;
import com.auction.backend.common.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/auction-persistence")
public class AuctionPersistenceAdminController {

    private final HotBidPersistenceCompensationService hotBidPersistenceCompensationService;

    public AuctionPersistenceAdminController(HotBidPersistenceCompensationService hotBidPersistenceCompensationService) {
        this.hotBidPersistenceCompensationService = hotBidPersistenceCompensationService;
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

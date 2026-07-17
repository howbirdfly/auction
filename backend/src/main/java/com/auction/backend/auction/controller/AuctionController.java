package com.auction.backend.auction.controller;

import com.auction.backend.auction.dto.AuctionLeaderboardEntry;
import com.auction.backend.auction.dto.AuctionQualificationSnapshot;
import com.auction.backend.auction.dto.AuctionRegistrationRequest;
import com.auction.backend.auction.dto.AuctionRegistrationSnapshot;
import com.auction.backend.auction.dto.AuctionRoomSnapshot;
import com.auction.backend.auction.dto.BidRequest;
import com.auction.backend.auction.dto.CreateAuctionRequest;
import com.auction.backend.auction.service.AuctionService;
import com.auction.backend.auction.service.AuctionQualificationService;
import com.auction.backend.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/auctions")
public class AuctionController {

    private final AuctionService auctionService;
    private final AuctionQualificationService auctionQualificationService;

    public AuctionController(AuctionService auctionService,
                             AuctionQualificationService auctionQualificationService) {
        this.auctionService = auctionService;
        this.auctionQualificationService = auctionQualificationService;
    }

    @GetMapping
    public ApiResponse<List<AuctionRoomSnapshot>> listRooms() {
        return ApiResponse.success(auctionService.listRooms());
    }

    @GetMapping("/{roomId}")
    public ApiResponse<AuctionRoomSnapshot> getRoom(@PathVariable String roomId) {
        return ApiResponse.success(auctionService.getRoom(roomId));
    }

    @GetMapping("/{roomId}/leaderboard")
    public ApiResponse<List<AuctionLeaderboardEntry>> getLeaderboard(@PathVariable String roomId) {
        return ApiResponse.success(auctionService.getLeaderboard(roomId));
    }

    @GetMapping("/{roomId}/qualifications/{userId}")
    public ApiResponse<AuctionQualificationSnapshot> getQualification(@PathVariable String roomId,
                                                                      @PathVariable String userId) {
        return ApiResponse.success(auctionQualificationService.getQualification(roomId, userId));
    }

    @PostMapping("/{roomId}/registrations")
    public ApiResponse<AuctionRegistrationSnapshot> registerForAuction(@PathVariable String roomId,
                                                                       @Valid @RequestBody AuctionRegistrationRequest request) {
        return ApiResponse.success("auction registration created", auctionQualificationService.register(roomId, request));
    }

    @PostMapping
    public ApiResponse<AuctionRoomSnapshot> createRoom(@Valid @RequestBody CreateAuctionRequest request) {
        return ApiResponse.success("auction room created", auctionService.createRoom(request));
    }

    @PostMapping("/{roomId}/bids")
    public ApiResponse<AuctionRoomSnapshot> placeBid(@PathVariable String roomId,
                                                     @Valid @RequestBody BidRequest request) {
        return ApiResponse.success("bid accepted", auctionService.placeBid(roomId, request));
    }

    @DeleteMapping("/{roomId}")
    public ApiResponse<Void> deleteExpiredRoom(@PathVariable String roomId) {
        auctionService.deleteExpiredRoom(roomId);
        return ApiResponse.success("expired auction room deleted", null);
    }
}

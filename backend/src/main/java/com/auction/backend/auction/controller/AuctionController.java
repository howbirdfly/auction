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
import com.auction.backend.security.AuctionPrincipal;
import com.auction.backend.security.AuthorizationService;
import com.auction.backend.user.service.UserService;
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
    private final AuthorizationService authorizationService;
    private final UserService userService;

    public AuctionController(AuctionService auctionService,
                             AuctionQualificationService auctionQualificationService,
                             AuthorizationService authorizationService,
                             UserService userService) {
        this.auctionService = auctionService;
        this.auctionQualificationService = auctionQualificationService;
        this.authorizationService = authorizationService;
        this.userService = userService;
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
        authorizationService.requireAccount(userId);
        return ApiResponse.success(auctionQualificationService.getQualification(roomId, userId));
    }

    @PostMapping("/{roomId}/registrations")
    public ApiResponse<AuctionRegistrationSnapshot> registerForAuction(@PathVariable String roomId,
                                                                       @Valid @RequestBody AuctionRegistrationRequest request) {
        authorizationService.requireAccount(request.userId());
        return ApiResponse.success("auction registration created", auctionQualificationService.register(roomId, request));
    }

    @PostMapping
    public ApiResponse<AuctionRoomSnapshot> createRoom(@Valid @RequestBody CreateAuctionRequest request) {
        AuctionPrincipal principal = authorizationService.requirePrincipal();
        String currentNickname = userService.getUser(principal.getUserId()).nickname();
        return ApiResponse.success(
                "auction room created",
                auctionService.createRoom(request, principal.getUserId(), currentNickname)
        );
    }

    @PostMapping("/{roomId}/bids")
    public ApiResponse<AuctionRoomSnapshot> placeBid(@PathVariable String roomId,
                                                     @Valid @RequestBody BidRequest request) {
        authorizationService.requireAccount(request.userId());
        return ApiResponse.success("bid accepted", auctionService.placeBid(roomId, request));
    }

    @DeleteMapping("/{roomId}")
    public ApiResponse<Void> deleteExpiredRoom(@PathVariable String roomId) {
        AuctionPrincipal principal = authorizationService.requirePrincipal();
        auctionService.deleteExpiredRoom(roomId, principal.getUserId(), principal.isAdmin());
        return ApiResponse.success("expired auction room deleted", null);
    }
}

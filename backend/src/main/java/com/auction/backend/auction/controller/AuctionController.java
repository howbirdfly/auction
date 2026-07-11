package com.auction.backend.auction.controller;

import com.auction.backend.auction.dto.AuctionRoomSnapshot;
import com.auction.backend.auction.dto.BidRequest;
import com.auction.backend.auction.dto.CreateAuctionRequest;
import com.auction.backend.auction.service.AuctionService;
import com.auction.backend.common.ApiResponse;
import jakarta.validation.Valid;
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

    public AuctionController(AuctionService auctionService) {
        this.auctionService = auctionService;
    }

    @GetMapping
    public ApiResponse<List<AuctionRoomSnapshot>> listRooms() {
        return ApiResponse.success(auctionService.listRooms());
    }

    @GetMapping("/{roomId}")
    public ApiResponse<AuctionRoomSnapshot> getRoom(@PathVariable String roomId) {
        return ApiResponse.success(auctionService.getRoom(roomId));
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
}

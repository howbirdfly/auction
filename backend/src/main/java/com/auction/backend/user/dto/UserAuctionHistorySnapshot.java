package com.auction.backend.user.dto;

import com.auction.backend.auction.dto.AuctionRoomSnapshot;

import java.util.List;

public record UserAuctionHistorySnapshot(
        List<AuctionRoomSnapshot> createdClosedRooms,
        List<AuctionRoomSnapshot> registeredRooms,
        List<AuctionRoomSnapshot> wonRooms,
        List<AuctionRoomSnapshot> missedRooms
) {
}

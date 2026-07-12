package com.auction.backend.auction.service;

import com.auction.backend.auction.dto.AuctionRoomSnapshot;
import com.auction.backend.auction.dto.BidRequest;

public interface BidEngine {

    AuctionRoomSnapshot placeBid(String roomId, BidRequest request);
}

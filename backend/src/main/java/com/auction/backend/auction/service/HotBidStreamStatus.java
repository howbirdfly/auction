package com.auction.backend.auction.service;

public record HotBidStreamStatus(
        String group,
        String consumer,
        long streamSize,
        long pendingCount
) {
}

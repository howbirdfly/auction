package com.auction.backend.auction.service;

public record HotBidStreamStatus(
        String group,
        String consumer,
        String lastDeliveredId,
        long streamSize,
        long pendingCount,
        long lag,
        long totalBacklog,
        boolean backpressured
) {
}

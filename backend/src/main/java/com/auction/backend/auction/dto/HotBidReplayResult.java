package com.auction.backend.auction.dto;

public record HotBidReplayResult(
        String scope,
        String target,
        int scanned,
        int replayed,
        int alreadyPersisted,
        int failed,
        long currentPersistedVersion
) {
}

package com.auction.backend.auction.dto;

import com.auction.backend.auction.service.HotRoomStatus;

public record HotRoomRecoveryResult(
        String roomId,
        String engineMode,
        HotRoomStatus status,
        long version
) {
}

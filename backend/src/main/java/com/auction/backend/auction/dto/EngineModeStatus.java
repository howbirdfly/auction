package com.auction.backend.auction.dto;

public record EngineModeStatus(
        String roomId,
        String engineMode,
        long version
) {
}

package com.auction.backend.auction.service;

import com.auction.backend.auction.model.AuctionStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class HotBidPersistenceEventPayloadTest {

    @Test
    void convertsRedisStreamPayloadToPersistenceMessage() {
        HotBidPersistenceEventPayload payload = new HotBidPersistenceEventPayload(
                "event-1",
                "request-1",
                "AR-1",
                "u10001",
                "Bidder A",
                "120.50",
                "",
                "0.00",
                7,
                1_700_000_000_123L,
                1_700_000_600_000L,
                AuctionStatus.BIDDING.name()
        );

        HotBidPersistenceMessage message = payload.toMessage();

        assertThat(message.eventId()).isEqualTo("event-1");
        assertThat(message.requestId()).isEqualTo("request-1");
        assertThat(message.amount()).isEqualByComparingTo(new BigDecimal("120.50"));
        assertThat(message.previousLeaderUserId()).isNull();
        assertThat(message.previousAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(message.bidTime()).isEqualTo(Instant.ofEpochMilli(1_700_000_000_123L));
        assertThat(message.roomStatus()).isEqualTo(AuctionStatus.BIDDING);
    }
}

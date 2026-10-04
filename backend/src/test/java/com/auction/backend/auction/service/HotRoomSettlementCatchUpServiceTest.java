package com.auction.backend.auction.service;

import com.auction.backend.auction.cache.AuctionCacheService;
import com.auction.backend.auction.dto.AuctionRoomSnapshot;
import com.auction.backend.auction.model.AuctionRoom;
import com.auction.backend.auction.model.AuctionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HotRoomSettlementCatchUpServiceTest {

    @Mock
    private AuctionCacheService auctionCacheService;
    @Mock
    private HotRoomManager hotRoomManager;
    @Mock
    private HotBidPersistenceCompensationService compensationService;
    @Mock
    private ObjectProvider<RedisHotBidStreamPersistenceService> streamServiceProvider;
    @Mock
    private RedisHotBidStreamPersistenceService streamService;

    @InjectMocks
    private HotRoomSettlementCatchUpService service;

    @Test
    void coldRoomCanSettleImmediately() {
        AuctionRoom room = room();
        when(hotRoomManager.status("AR-1")).thenReturn(HotRoomStatus.COLD);

        assertThat(service.isReadyForSettlement(room)).isTrue();
    }

    @Test
    void redisUnavailableBlocksSettlement() {
        AuctionRoom room = room();
        when(hotRoomManager.status("AR-1")).thenReturn(HotRoomStatus.REDIS_UNAVAILABLE);

        assertThat(service.isReadyForSettlement(room)).isFalse();
    }

    @Test
    void hotRoomMustCatchUpBeforeSettlement() {
        AuctionRoom room = room();
        AuctionRoomSnapshot snapshot = org.mockito.Mockito.mock(AuctionRoomSnapshot.class);
        when(snapshot.status()).thenReturn(AuctionStatus.CLOSED);
        when(snapshot.version()).thenReturn(8L);
        when(hotRoomManager.status("AR-1")).thenReturn(HotRoomStatus.HOT);
        when(auctionCacheService.getRoom("AR-1")).thenReturn(Optional.of(snapshot));
        when(streamServiceProvider.getIfAvailable()).thenReturn(streamService);
        when(streamService.catchUpRoom("AR-1", 7L)).thenReturn(true);

        assertThat(service.isReadyForSettlement(room)).isTrue();
    }

    @Test
    void missingHotSnapshotBlocksSettlement() {
        AuctionRoom room = room();
        when(hotRoomManager.status("AR-1")).thenReturn(HotRoomStatus.HOT);
        when(auctionCacheService.getRoom("AR-1")).thenReturn(Optional.empty());

        assertThat(service.isReadyForSettlement(room)).isFalse();
    }

    private AuctionRoom room() {
        AuctionRoom room = new AuctionRoom();
        room.setRoomId("AR-1");
        room.setStatus(AuctionStatus.CLOSED);
        room.setVersion(8L);
        return room;
    }
}

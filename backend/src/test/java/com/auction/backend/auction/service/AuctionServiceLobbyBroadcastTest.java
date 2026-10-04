package com.auction.backend.auction.service;

import com.auction.backend.auction.dto.AuctionRoomSnapshot;
import com.auction.backend.auction.dto.BidRequest;
import com.auction.backend.auction.mapper.AuctionBidRecordMapper;
import com.auction.backend.auction.mapper.AuctionRoomMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuctionServiceLobbyBroadcastTest {

    @Mock
    private AuctionRoomMapper auctionRoomMapper;
    @Mock
    private AuctionBidRecordMapper auctionBidRecordMapper;
    @Mock
    private AuctionBroadcastService broadcastService;
    @Mock
    private AuctionRoomReadService auctionRoomReadService;
    @Mock
    private BidEngineRouter bidEngineRouter;
    @Mock
    private HotRoomManager hotRoomManager;
    @Mock
    private AuctionQualificationService auctionQualificationService;
    @Mock
    private AuctionWalletService auctionWalletService;
    @Mock
    private AuctionSettlementService auctionSettlementService;

    @InjectMocks
    private AuctionService auctionService;

    @Test
    void coalescesLobbyRefreshesAcrossBids() {
        BidRequest request = new BidRequest(
                "request-1",
                "u10001",
                "Bidder A",
                BigDecimal.TEN
        );
        AuctionRoomSnapshot snapshot = org.mockito.Mockito.mock(AuctionRoomSnapshot.class);
        when(bidEngineRouter.placeBid("AR-1", request)).thenReturn(snapshot);
        auctionService.placeBid("AR-1", request);
        auctionService.placeBid("AR-1", request);

        verify(auctionRoomReadService, never()).refreshLobbyCache();
        verify(broadcastService, times(2)).broadcastRoom(snapshot);

        auctionService.flushLobbyBroadcast();

        verify(auctionRoomReadService, times(1)).refreshLobbyCache();
        verify(broadcastService, times(1)).broadcastLobby(List.of());
    }
}

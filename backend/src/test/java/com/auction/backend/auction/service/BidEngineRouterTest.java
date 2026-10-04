package com.auction.backend.auction.service;

import com.auction.backend.auction.dto.AuctionRoomSnapshot;
import com.auction.backend.auction.dto.BidRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BidEngineRouterTest {

    private final MysqlBidEngine mysqlBidEngine = mock(MysqlBidEngine.class);
    private final RedisBidEngine redisBidEngine = mock(RedisBidEngine.class);
    private final HotRoomManager hotRoomManager = mock(HotRoomManager.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<RedisBidEngine> redisProvider = mock(ObjectProvider.class);
    private final BidEngineRouter router = new BidEngineRouter(
            mysqlBidEngine,
            redisProvider,
            hotRoomManager
    );

    @Test
    void routesHotRoomsToRedis() {
        BidRequest request = request("hot-bid");
        AuctionRoomSnapshot expected = mock(AuctionRoomSnapshot.class);
        when(hotRoomManager.status("AR-1")).thenReturn(HotRoomStatus.HOT);
        when(redisProvider.getIfAvailable()).thenReturn(redisBidEngine);
        when(redisBidEngine.placeBid("AR-1", request)).thenReturn(expected);

        assertThat(router.placeBid("AR-1", request)).isSameAs(expected);

        verify(redisBidEngine).placeBid("AR-1", request);
        verify(mysqlBidEngine, never()).placeBid("AR-1", request);
    }

    @Test
    void routesColdRoomsToMysql() {
        BidRequest request = request("cold-bid");
        AuctionRoomSnapshot expected = mock(AuctionRoomSnapshot.class);
        when(hotRoomManager.status("AR-2")).thenReturn(HotRoomStatus.COLD);
        when(mysqlBidEngine.placeBid("AR-2", request)).thenReturn(expected);

        assertThat(router.placeBid("AR-2", request)).isSameAs(expected);

        verify(mysqlBidEngine).placeBid("AR-2", request);
        verify(redisBidEngine, never()).placeBid("AR-2", request);
    }

    @Test
    void failsClosedWhenRedisBeanIsUnavailableForHotRoom() {
        BidRequest request = request("redis-disabled");
        when(hotRoomManager.status("AR-3")).thenReturn(HotRoomStatus.HOT);
        when(redisProvider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> router.placeBid("AR-3", request))
                .hasMessageContaining("hot auction engine is unavailable");

        verify(mysqlBidEngine, never()).placeBid("AR-3", request);
    }

    @Test
    void doesNotSilentlyFallbackWhenRedisBidFails() {
        BidRequest request = request("redis-failure");
        IllegalStateException failure = new IllegalStateException("redis unavailable");
        when(hotRoomManager.status("AR-4")).thenReturn(HotRoomStatus.HOT);
        when(redisProvider.getIfAvailable()).thenReturn(redisBidEngine);
        when(redisBidEngine.placeBid("AR-4", request)).thenThrow(failure);

        assertThatThrownBy(() -> router.placeBid("AR-4", request))
                .isSameAs(failure);

        verify(mysqlBidEngine, never()).placeBid("AR-4", request);
    }

    @Test
    void failsClosedWhileRedisIsUnavailable() {
        BidRequest request = request("redis-unavailable");
        when(hotRoomManager.status("AR-5")).thenReturn(HotRoomStatus.REDIS_UNAVAILABLE);

        assertThatThrownBy(() -> router.placeBid("AR-5", request))
                .hasMessageContaining("temporarily unavailable");

        verify(mysqlBidEngine, never()).placeBid("AR-5", request);
        verify(redisBidEngine, never()).placeBid("AR-5", request);
    }

    private BidRequest request(String requestId) {
        return new BidRequest(requestId, "u10001", "Bidder A", BigDecimal.valueOf(100));
    }
}

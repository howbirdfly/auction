package com.auction.backend.auction.service;

import com.auction.backend.auction.cache.AuctionCacheService;
import com.auction.backend.auction.config.AuctionCacheProperties;
import com.auction.backend.auction.mapper.AuctionRoomMapper;
import com.auction.backend.auction.mapper.AuctionRoomRegistrationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisHotRoomManagerTest {

    private final StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
    private final AuctionCacheService auctionCacheService = mock(AuctionCacheService.class);
    private final AuctionRoomRegistrationMapper registrationMapper =
            mock(AuctionRoomRegistrationMapper.class);
    private final AuctionRoomMapper auctionRoomMapper = mock(AuctionRoomMapper.class);
    private final RedisHotRoomRateTracker rateTracker = mock(RedisHotRoomRateTracker.class);
    private final AuctionCacheProperties properties = new AuctionCacheProperties();

    private RedisHotRoomManager manager;

    @BeforeEach
    void setUp() {
        properties.setHotAccessThreshold(30);
        properties.setHotBidEnterThreshold(25);
        properties.setHotBidEnterWindow(Duration.ofSeconds(5));
        properties.setHotBidExitThreshold(6);
        properties.setHotBidExitWindow(Duration.ofSeconds(60));
        manager = new RedisHotRoomManager(
                stringRedisTemplate,
                auctionCacheService,
                properties,
                registrationMapper,
                auctionRoomMapper,
                rateTracker
        );
    }

    @Test
    void usesSharedBidWindowForPromotionThreshold() {
        when(rateTracker.recordBid("AR-1", Duration.ofSeconds(5)))
                .thenReturn(124L, 125L);

        assertThat(manager.recordBid("AR-1")).isFalse();
        assertThat(manager.recordBid("AR-1")).isTrue();

        verify(rateTracker, org.mockito.Mockito.times(2))
                .recordBid("AR-1", Duration.ofSeconds(5));
    }

    @Test
    void usesSharedBidWindowForExitThreshold() {
        when(rateTracker.countBids("AR-1", Duration.ofSeconds(60)))
                .thenReturn(0L, 359L, 360L);

        assertThat(manager.shouldStayHot("AR-1")).isTrue();
        assertThat(manager.shouldStayHot("AR-1")).isFalse();
        assertThat(manager.shouldStayHot("AR-1")).isTrue();
    }

    @Test
    void recordsViewsThroughSharedCounter() {
        when(rateTracker.recordView("AR-1")).thenReturn(29L, 30L);

        assertThat(manager.recordAccess("AR-1")).isFalse();
        assertThat(manager.recordAccess("AR-1")).isTrue();
    }
}

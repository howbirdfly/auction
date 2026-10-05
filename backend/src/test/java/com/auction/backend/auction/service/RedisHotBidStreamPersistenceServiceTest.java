package com.auction.backend.auction.service;

import com.auction.backend.auction.config.AuctionCacheProperties;
import com.auction.backend.auction.mapper.AuctionBidRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisHotBidStreamPersistenceServiceTest {

    private final StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final StreamOperations<String, Object, Object> streamOperations =
            mock(StreamOperations.class);
    private final HotBidPersistenceStore persistenceStore = mock(HotBidPersistenceStore.class);
    private final HotBidPersistenceLogService persistenceLogService =
            mock(HotBidPersistenceLogService.class);
    private final AuctionBidRecordMapper bidRecordMapper = mock(AuctionBidRecordMapper.class);
    private final AuctionCacheProperties properties = new AuctionCacheProperties();

    private RedisHotBidStreamPersistenceService service;

    @BeforeEach
    void setUp() {
        properties.setHotBidStreamKey("auction:test:stream");
        properties.setHotBidStreamGroup("auction-test-group");
        properties.setHotBidStreamMaxBacklog(10L);
        when(stringRedisTemplate.opsForStream()).thenReturn(streamOperations);
        when(stringRedisTemplate.hasKey("auction:test:stream")).thenReturn(true);
        when(streamOperations.createGroup(
                "auction:test:stream",
                ReadOffset.from("0-0"),
                "auction-test-group"
        )).thenReturn("OK");
        service = new RedisHotBidStreamPersistenceService(
                stringRedisTemplate,
                properties,
                persistenceStore,
                persistenceLogService,
                bidRecordMapper,
                mock(JsonMapper.class)
        );
    }

    @Test
    void reportsPendingAndUndeliveredLagSeparately() {
        mockStreamBacklog(20L, 3L, 7L);

        HotBidStreamStatus status = service.status();

        assertThat(status.streamSize()).isEqualTo(20L);
        assertThat(status.lastDeliveredId()).isEqualTo("10-0");
        assertThat(status.pendingCount()).isEqualTo(3L);
        assertThat(status.lag()).isEqualTo(7L);
        assertThat(status.totalBacklog()).isEqualTo(10L);
        assertThat(status.backpressured()).isFalse();
    }

    @Test
    void appliesBackpressureWhenPendingAndLagExceedLimit() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        mockStreamBacklog(20L, 3L, 8L);

        service.updateBackpressureState();

        verify(valueOperations).set(
                "auction:hot-bid:stream:backpressure",
                "total=11,pending=3,lag=8"
        );
    }

    private void mockStreamBacklog(long streamSize, long pendingCount, long lag) {
        StreamInfo.XInfoGroups groups = mock(StreamInfo.XInfoGroups.class);
        StreamInfo.XInfoGroup group = mock(StreamInfo.XInfoGroup.class);
        when(streamOperations.size("auction:test:stream")).thenReturn(streamSize);
        when(streamOperations.groups("auction:test:stream")).thenReturn(groups);
        when(groups.stream()).thenReturn(Stream.of(group));
        when(group.groupName()).thenReturn("auction-test-group");
        when(group.lastDeliveredId()).thenReturn("10-0");
        when(group.pendingCount()).thenReturn(pendingCount);
        when(group.getRaw()).thenReturn(Map.of("lag", lag));
    }
}

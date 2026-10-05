package com.auction.backend.auction.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisHotRoomRateTrackerTest {

    private final StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
    private final RedisHotRoomRateTracker tracker =
            new RedisHotRoomRateTracker(stringRedisTemplate);

    @Test
    @SuppressWarnings("unchecked")
    void recordsAtSecondAndWindowInOneRedisCall() {
        when(stringRedisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                any(),
                any(),
                any()
        )).thenReturn(125L);

        long count = tracker.recordAndCount(
                "AR-1",
                "bid",
                Duration.ofSeconds(5)
        );

        assertThat(count).isEqualTo(125L);
        verify(stringRedisTemplate).execute(
                any(RedisScript.class),
                eq(List.of("auction:room:AR-1:hot-rate")),
                eq("bid"),
                eq("35"),
                eq("5")
        );
    }

    @Test
    void ignoresBlankRooms() {
        assertThat(tracker.recordAndCount(
                " ",
                "bid",
                Duration.ofSeconds(5)
        )).isZero();
    }
}

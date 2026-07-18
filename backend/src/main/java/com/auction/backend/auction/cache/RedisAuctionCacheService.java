package com.auction.backend.auction.cache;

import com.auction.backend.auction.config.AuctionCacheProperties;
import com.auction.backend.auction.dto.AuctionLeaderboardEntry;
import com.auction.backend.auction.dto.AuctionRoomSnapshot;
import com.auction.backend.auction.model.AuctionStatus;
import com.auction.backend.auction.model.BidRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@ConditionalOnProperty(name = "auction.cache.redis.enabled", havingValue = "true")
public class RedisAuctionCacheService implements AuctionCacheService {

    private static final Logger log = LoggerFactory.getLogger(RedisAuctionCacheService.class);
    private static final String LOBBY_KEY = "auction:lobby:list";
    private static final int RECENT_BID_LIMIT = 10;
    private static final TypeReference<List<AuctionRoomSnapshot>> LOBBY_TYPE = new TypeReference<>() {
    };

    private final StringRedisTemplate stringRedisTemplate;
    private final JsonMapper jsonMapper;
    private final AuctionCacheProperties cacheProperties;

    public RedisAuctionCacheService(StringRedisTemplate stringRedisTemplate,
                                    JsonMapper jsonMapper,
                                    AuctionCacheProperties cacheProperties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.jsonMapper = jsonMapper;
        this.cacheProperties = cacheProperties;
    }

    @Override
    public Optional<List<AuctionRoomSnapshot>> getLobby() {
        return readValue(LOBBY_KEY, LOBBY_TYPE)
                .map(rooms -> rooms.stream().map(this::refreshSnapshot).toList());
    }

    @Override
    public Optional<AuctionRoomSnapshot> getRoom(String roomId) {
        Optional<AuctionRoomSnapshot> hotRoomSnapshot = readHotRoom(roomId);
        if (hotRoomSnapshot.isPresent()) {
            return hotRoomSnapshot;
        }

        return readValue(roomKey(roomId), AuctionRoomSnapshot.class)
                .map(this::refreshSnapshot);
    }

    @Override
    public Optional<List<AuctionLeaderboardEntry>> getLeaderboard(String roomId) {
        try {
            String key = leaderboardKey(roomId);
            Boolean exists = stringRedisTemplate.hasKey(key);
            if (!Boolean.TRUE.equals(exists)) {
                return Optional.empty();
            }

            Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                    .reverseRangeWithScores(key, 0, 9);
            if (tuples == null || tuples.isEmpty()) {
                return Optional.of(List.of());
            }

            List<AuctionLeaderboardEntry> entries = new ArrayList<>();
            int rank = 1;
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                if (tuple == null || tuple.getValue() == null || tuple.getScore() == null) {
                    continue;
                }
                String userId = tuple.getValue();
                String nickname = stringRedisTemplate.opsForHash().get(leaderboardProfileKey(roomId), userId) instanceof String value
                        ? value
                        : userId;
                entries.add(new AuctionLeaderboardEntry(
                        rank++,
                        userId,
                        nickname,
                        BigDecimal.valueOf(tuple.getScore())
                ));
            }
            return Optional.of(entries);
        } catch (Exception exception) {
            log.warn("Failed to read Redis leaderboard for room {}", roomId, exception);
            evictLeaderboard(roomId);
            return Optional.empty();
        }
    }

    @Override
    public Optional<List<BidRecord>> getRecentBids(String roomId) {
        try {
            String key = recentBidKey(roomId);
            Boolean exists = stringRedisTemplate.hasKey(key);
            if (!Boolean.TRUE.equals(exists)) {
                return Optional.empty();
            }

            List<String> bidPayloads = stringRedisTemplate.opsForList().range(key, 0, RECENT_BID_LIMIT - 1);
            if (bidPayloads == null || bidPayloads.isEmpty()) {
                return Optional.of(List.of());
            }

            return Optional.of(readBidRecords(bidPayloads));
        } catch (Exception exception) {
            log.warn("Failed to read Redis recent bids for room {}", roomId, exception);
            evictRecentBids(roomId);
            return Optional.empty();
        }
    }

    @Override
    public void cacheLobby(List<AuctionRoomSnapshot> rooms) {
        writeValue(LOBBY_KEY, rooms, cacheProperties.getLobbyTtl());
    }

    @Override
    public void cacheRoom(AuctionRoomSnapshot room) {
        writeValue(roomKey(room.roomId()), room, resolveRoomTtl(room));
        if (shouldCacheAsHotRoom(room)) {
            cacheHotRoomState(room);
        } else {
            deleteKey(hotStateKey(room.roomId()));
        }
    }

    @Override
    public void cacheLeaderboard(String roomId, List<AuctionLeaderboardEntry> entries, AuctionRoomSnapshot room) {
        evictLeaderboard(roomId);
        if (entries.isEmpty()) {
            return;
        }

        Duration ttl = resolveLeaderboardTtl(room);

        try {
            for (AuctionLeaderboardEntry entry : entries) {
                stringRedisTemplate.opsForZSet().add(leaderboardKey(roomId), entry.userId(), entry.amount().doubleValue());
                stringRedisTemplate.opsForHash().put(leaderboardProfileKey(roomId), entry.userId(), entry.nickname());
            }
            stringRedisTemplate.expire(leaderboardKey(roomId), ttl);
            stringRedisTemplate.expire(leaderboardProfileKey(roomId), ttl);
        } catch (Exception exception) {
            log.warn("Failed to write Redis leaderboard for room {}", roomId, exception);
        }
    }

    @Override
    public void cacheRecentBids(String roomId, List<BidRecord> recentBids, AuctionRoomSnapshot room) {
        evictRecentBids(roomId);
        if (recentBids.isEmpty()) {
            return;
        }

        Duration ttl = resolveRoomTtl(room);

        try {
            List<String> bidPayloads = recentBids.stream()
                    .map(this::serializeBidRecord)
                    .filter(payload -> payload != null && !payload.isBlank())
                    .toList();

            if (bidPayloads.isEmpty()) {
                return;
            }

            stringRedisTemplate.opsForList().rightPushAll(recentBidKey(roomId), bidPayloads);
            stringRedisTemplate.opsForList().trim(recentBidKey(roomId), 0, RECENT_BID_LIMIT - 1);
            stringRedisTemplate.expire(recentBidKey(roomId), ttl);
        } catch (Exception exception) {
            log.warn("Failed to write Redis recent bids for room {}", roomId, exception);
        }
    }

    @Override
    public void recordBid(String roomId, String userId, String nickname, BigDecimal amount, AuctionRoomSnapshot room) {
        Duration ttl = resolveLeaderboardTtl(room);

        try {
            stringRedisTemplate.opsForZSet().add(leaderboardKey(roomId), userId, amount.doubleValue());
            stringRedisTemplate.opsForHash().put(leaderboardProfileKey(roomId), userId, nickname);
            stringRedisTemplate.expire(leaderboardKey(roomId), ttl);
            stringRedisTemplate.expire(leaderboardProfileKey(roomId), ttl);

            Boolean recentBidKeyExists = stringRedisTemplate.hasKey(recentBidKey(roomId));
            if (Boolean.TRUE.equals(recentBidKeyExists) && !room.recentBids().isEmpty()) {
                String latestBid = serializeBidRecord(room.recentBids().get(0));
                if (latestBid != null && !latestBid.isBlank()) {
                    stringRedisTemplate.opsForList().leftPush(recentBidKey(roomId), latestBid);
                    stringRedisTemplate.opsForList().trim(recentBidKey(roomId), 0, RECENT_BID_LIMIT - 1);
                    stringRedisTemplate.expire(recentBidKey(roomId), resolveRoomTtl(room));
                }
            } else if (!room.recentBids().isEmpty()) {
                cacheRecentBids(roomId, room.recentBids(), room);
            }
        } catch (Exception exception) {
            log.warn("Failed to update Redis hot room state for room {}", roomId, exception);
        }
    }

    @Override
    public void evictLobby() {
        deleteKey(LOBBY_KEY);
    }

    @Override
    public void evictRoom(String roomId) {
        deleteKey(roomKey(roomId));
        deleteKey(hotStateKey(roomId));
    }

    @Override
    public void evictLeaderboard(String roomId) {
        deleteKey(leaderboardKey(roomId));
        deleteKey(leaderboardProfileKey(roomId));
    }

    @Override
    public void evictRecentBids(String roomId) {
        deleteKey(recentBidKey(roomId));
    }

    private <T> Optional<T> readValue(String key, Class<T> targetType) {
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(jsonMapper.readValue(json, targetType));
        } catch (Exception exception) {
            log.warn("Failed to read Redis cache key {}", key, exception);
            deleteKey(key);
            return Optional.empty();
        }
    }

    private <T> Optional<T> readValue(String key, TypeReference<T> targetType) {
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(jsonMapper.readValue(json, targetType));
        } catch (Exception exception) {
            log.warn("Failed to read Redis cache key {}", key, exception);
            deleteKey(key);
            return Optional.empty();
        }
    }

    private void writeValue(String key, Object value, java.time.Duration ttl) {
        try {
            stringRedisTemplate.opsForValue().set(key, jsonMapper.writeValueAsString(value), ttl);
        } catch (Exception exception) {
            log.warn("Failed to write Redis cache key {}", key, exception);
        }
    }

    private void deleteKey(String key) {
        try {
            stringRedisTemplate.delete(key);
        } catch (Exception exception) {
            log.warn("Failed to delete Redis cache key {}", key, exception);
        }
    }

    private String roomKey(String roomId) {
        return "auction:room:" + roomId;
    }

    private String hotStateKey(String roomId) {
        return "auction:room:" + roomId + ":hot-state";
    }

    private String leaderboardKey(String roomId) {
        return "auction:room:" + roomId + ":leaderboard";
    }

    private String leaderboardProfileKey(String roomId) {
        return "auction:room:" + roomId + ":leaderboard:profile";
    }

    private String recentBidKey(String roomId) {
        return "auction:room:" + roomId + ":recent-bids";
    }

    private Duration resolveRoomTtl(AuctionRoomSnapshot room) {
        if (shouldUseHotTtl(room)) {
            return Duration.ofSeconds(Math.max(1, room.secondsRemaining())).plus(cacheProperties.getHotRoomBuffer());
        }
        return cacheProperties.getRoomTtl();
    }

    private Duration resolveLeaderboardTtl(AuctionRoomSnapshot room) {
        if (shouldUseHotTtl(room)) {
            return Duration.ofSeconds(Math.max(1, room.secondsRemaining())).plus(cacheProperties.getHotRoomBuffer());
        }
        return cacheProperties.getLeaderboardTtl();
    }

    private boolean shouldCacheAsHotRoom(AuctionRoomSnapshot room) {
        return room.status() == AuctionStatus.BIDDING
                && room.secondsRemaining() > 0
                && isRoomMarkedHot(room.roomId());
    }

    private boolean shouldUseHotTtl(AuctionRoomSnapshot room) {
        return room.status() == AuctionStatus.BIDDING
                && room.secondsRemaining() > 0
                && isRoomMarkedHot(room.roomId());
    }

    private boolean isRoomMarkedHot(String roomId) {
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey("auction:room:" + roomId + ":mode"));
        } catch (Exception exception) {
            log.warn("Failed to read hot room mode for room {}", roomId, exception);
            return false;
        }
    }

    private AuctionRoomSnapshot refreshSnapshot(AuctionRoomSnapshot room) {
        long secondsRemaining = room.status() == AuctionStatus.CLOSED
                ? 0
                : Math.max(0, Duration.between(Instant.now(), room.endsAt()).toSeconds());

        return new AuctionRoomSnapshot(
                room.roomId(),
                room.itemTitle(),
                room.anchorName(),
                room.imageUrl(),
                room.status(),
                room.startPrice(),
                room.currentPrice(),
                room.stepPrice(),
                room.minNextBid(),
                room.leaderNickname(),
                room.registrationRequired(),
                room.depositAmount(),
                isRoomMarkedHot(room.roomId()),
                room.version(),
                room.endsAt(),
                secondsRemaining,
                room.bidCount(),
                room.recentBids()
        );
    }

    private String serializeBidRecord(BidRecord bidRecord) {
        try {
            return jsonMapper.writeValueAsString(new CachedBidRecordPayload(
                    bidRecord.userId(),
                    bidRecord.nickname(),
                    bidRecord.amount().toPlainString(),
                    bidRecord.version(),
                    bidRecord.bidTime().toEpochMilli()
            ));
        } catch (Exception exception) {
            log.warn("Failed to serialize bid record for Redis cache", exception);
            return null;
        }
    }

    private void cacheHotRoomState(AuctionRoomSnapshot room) {
        Duration ttl = resolveRoomTtl(room);

        try {
            Map<String, String> state = new HashMap<>();
            state.put("roomId", room.roomId());
            state.put("itemTitle", room.itemTitle());
            state.put("anchorName", room.anchorName());
            state.put("imageUrl", room.imageUrl());
            state.put("status", room.status().name());
            state.put("startPrice", room.startPrice().toPlainString());
            state.put("currentPrice", room.currentPrice().toPlainString());
            state.put("stepPrice", room.stepPrice().toPlainString());
            state.put("minNextBid", room.minNextBid().toPlainString());
            state.put("leaderUserId", room.recentBids().isEmpty() ? "" : room.recentBids().get(0).userId());
            state.put("leaderNickname", room.leaderNickname() == null ? "" : room.leaderNickname());
            state.put("registrationRequired", Boolean.toString(room.registrationRequired()));
            state.put("depositAmount", room.depositAmount().toPlainString());
            state.put("version", Long.toString(room.version()));
            state.put("endsAtEpochMilli", Long.toString(room.endsAt().toEpochMilli()));
            state.put("bidCount", Integer.toString(room.bidCount()));
            stringRedisTemplate.opsForHash().putAll(hotStateKey(room.roomId()), state);
            stringRedisTemplate.expire(hotStateKey(room.roomId()), ttl);
        } catch (Exception exception) {
            log.warn("Failed to write Redis hot room state for room {}", room.roomId(), exception);
        }
    }

    private Optional<AuctionRoomSnapshot> readHotRoom(String roomId) {
        try {
            Map<Object, Object> state = stringRedisTemplate.opsForHash().entries(hotStateKey(roomId));
            if (state == null || state.isEmpty()) {
                return Optional.empty();
            }

            AuctionStatus status = AuctionStatus.valueOf(readStateValue(state, "status"));
            Instant endsAt = Instant.ofEpochMilli(Long.parseLong(readStateValue(state, "endsAtEpochMilli")));
            List<BidRecord> recentBids = getRecentBids(roomId).orElse(List.of());

            return Optional.of(new AuctionRoomSnapshot(
                    readStateValue(state, "roomId"),
                    readStateValue(state, "itemTitle"),
                    readStateValue(state, "anchorName"),
                    readStateValue(state, "imageUrl"),
                    status,
                    new BigDecimal(readStateValue(state, "startPrice")),
                    new BigDecimal(readStateValue(state, "currentPrice")),
                    new BigDecimal(readStateValue(state, "stepPrice")),
                    new BigDecimal(readStateValue(state, "minNextBid")),
                    emptyToNull(readStateValue(state, "leaderNickname")),
                    Boolean.parseBoolean(readStateOrDefault(state, "registrationRequired", "false")),
                    new BigDecimal(readStateOrDefault(state, "depositAmount", "0")),
                    true,
                    Long.parseLong(readStateOrDefault(state, "version", "0")),
                    endsAt,
                    status == AuctionStatus.CLOSED ? 0 : Math.max(0, Duration.between(Instant.now(), endsAt).toSeconds()),
                    Integer.parseInt(readStateValue(state, "bidCount")),
                    recentBids
            ));
        } catch (Exception exception) {
            log.warn("Failed to read Redis hot room state for room {}", roomId, exception);
            deleteKey(hotStateKey(roomId));
            return Optional.empty();
        }
    }

    private List<BidRecord> readBidRecords(List<String> bidPayloads) throws Exception {
        List<BidRecord> recentBids = new ArrayList<>(bidPayloads.size());
        for (String bidPayload : bidPayloads) {
            if (bidPayload == null || bidPayload.isBlank()) {
                continue;
            }
            CachedBidRecordPayload payload = jsonMapper.readValue(bidPayload, CachedBidRecordPayload.class);
            recentBids.add(new BidRecord(
                    payload.userId(),
                    payload.nickname(),
                    new BigDecimal(payload.amount()),
                    payload.version(),
                    Instant.ofEpochMilli(payload.bidTimeEpochMilli())
            ));
        }
        return recentBids;
    }

    private String readStateValue(Map<Object, Object> state, String field) {
        Object value = state.get(field);
        return value == null ? "" : value.toString();
    }

    private String readStateOrDefault(Map<Object, Object> state, String field, String defaultValue) {
        String value = readStateValue(state, field);
        return value.isBlank() ? defaultValue : value;
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record CachedBidRecordPayload(
            String userId,
            String nickname,
            String amount,
            long version,
            long bidTimeEpochMilli
    ) {
    }
}

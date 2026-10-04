package com.auction.backend.auction.service;

import com.auction.backend.auction.config.AuctionCacheProperties;
import com.auction.backend.auction.mapper.AuctionBidRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(name = "auction.cache.redis.enabled", havingValue = "true")
public class RedisHotBidStreamPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(RedisHotBidStreamPersistenceService.class);
    private static final int MAX_CATCH_UP_BATCHES = 20;

    private final StringRedisTemplate stringRedisTemplate;
    private final AuctionCacheProperties auctionCacheProperties;
    private final HotBidPersistenceStore hotBidPersistenceStore;
    private final HotBidPersistenceLogService hotBidPersistenceLogService;
    private final AuctionBidRecordMapper auctionBidRecordMapper;
    private final JsonMapper jsonMapper;

    public RedisHotBidStreamPersistenceService(StringRedisTemplate stringRedisTemplate,
                                               AuctionCacheProperties auctionCacheProperties,
                                               HotBidPersistenceStore hotBidPersistenceStore,
                                               HotBidPersistenceLogService hotBidPersistenceLogService,
                                               AuctionBidRecordMapper auctionBidRecordMapper,
                                               JsonMapper jsonMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.auctionCacheProperties = auctionCacheProperties;
        this.hotBidPersistenceStore = hotBidPersistenceStore;
        this.hotBidPersistenceLogService = hotBidPersistenceLogService;
        this.auctionBidRecordMapper = auctionBidRecordMapper;
        this.jsonMapper = jsonMapper;
    }

    @Scheduled(fixedDelayString = "${auction.cache.redis.hot-bid-stream-poll-interval-ms:100}")
    public void consumePendingEvents() {
        consumeBatch(auctionCacheProperties.getHotBidStreamBatchSize());
    }

    public boolean catchUpRoom(String roomId, long targetVersion) {
        if (roomId == null || roomId.isBlank() || targetVersion <= 0) {
            return true;
        }

        for (int attempt = 0; attempt < MAX_CATCH_UP_BATCHES; attempt++) {
            if (currentPersistedVersion(roomId) >= targetVersion) {
                return true;
            }
            int processed = consumeBatch(auctionCacheProperties.getHotBidStreamBatchSize());
            if (processed == 0) {
                break;
            }
        }
        return currentPersistedVersion(roomId) >= targetVersion;
    }

    public synchronized int consumeBatch(int batchSize) {
        List<MapRecord<String, Object, Object>> records;
        try {
            StreamOperations<String, Object, Object> streamOperations = stringRedisTemplate.opsForStream();
            records = streamOperations.range(
                    auctionCacheProperties.getHotBidStreamKey(),
                    org.springframework.data.domain.Range.unbounded(),
                    Limit.limit().count(batchSize)
            );
        } catch (RuntimeException exception) {
            log.warn("Failed to read hot bid persistence stream", exception);
            return 0;
        }

        if (records == null || records.isEmpty()) {
            return 0;
        }

        int processed = 0;
        for (MapRecord<String, Object, Object> record : records) {
            if (processRecord(record)) {
                processed++;
            }
        }
        return processed;
    }

    private boolean processRecord(MapRecord<String, Object, Object> record) {
        String lockKey = "auction:hot-bid:consume:" + record.getId().getValue();
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(30));
        if (!Boolean.TRUE.equals(acquired)) {
            return false;
        }

        Object rawPayload = record.getValue().get("payload");
        if (rawPayload == null || rawPayload.toString().isBlank()) {
            moveToDeadLetter(record.getId().getValue(), "", "missing payload");
            deleteRecord(record.getId().getValue());
            releaseLock(lockKey);
            return true;
        }

        String payload = rawPayload.toString();
        HotBidPersistenceMessage message;
        try {
            message = jsonMapper.readValue(payload, HotBidPersistenceEventPayload.class)
                    .toMessage();
        } catch (Exception exception) {
            log.error("Failed to parse hot bid stream event {}", record.getId(), exception);
            moveToDeadLetter(record.getId().getValue(), payload, exception.getMessage());
            deleteRecord(record.getId().getValue());
            releaseLock(lockKey);
            return true;
        }

        try {
            hotBidPersistenceLogService.markProcessing(message);
            hotBidPersistenceStore.persist(message);
            hotBidPersistenceLogService.markSuccess(message);
            deleteRecord(record.getId().getValue());
            releaseLock(lockKey);
            return true;
        } catch (Exception exception) {
            log.error("Failed to persist hot bid stream event {}", record.getId(), exception);
            try {
                hotBidPersistenceLogService.markFailed(message, exception);
            } catch (Exception ignored) {
                // Keep the stream entry so the next scheduler pass can retry.
            }
            releaseLock(lockKey);
            return false;
        }
    }

    private void moveToDeadLetter(String sourceRecordId, String payload, String error) {
        try {
            stringRedisTemplate.opsForStream().add(
                    auctionCacheProperties.getHotBidDeadLetterStreamKey(),
                    Map.of(
                            "sourceRecordId", sourceRecordId,
                            "payload", payload,
                            "error", error == null ? "unknown error" : error
                    )
            );
        } catch (RuntimeException exception) {
            log.error("Failed to move hot bid stream event {} to dead letter stream", sourceRecordId, exception);
        }
    }

    private void deleteRecord(String recordId) {
        try {
            stringRedisTemplate.opsForStream().delete(
                    auctionCacheProperties.getHotBidStreamKey(),
                    recordId
            );
        } catch (RuntimeException exception) {
            log.warn("Failed to delete persisted hot bid stream event {}", recordId, exception);
        }
    }

    private void releaseLock(String lockKey) {
        try {
            stringRedisTemplate.delete(lockKey);
        } catch (RuntimeException exception) {
            log.warn("Failed to release hot bid stream record lock {}", lockKey, exception);
        }
    }

    private long currentPersistedVersion(String roomId) {
        Long version = auctionBidRecordMapper.findMaxVersionByRoomId(roomId);
        return version == null ? 0L : version;
    }
}

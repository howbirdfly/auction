package com.auction.backend.auction.service;

import com.auction.backend.auction.config.AuctionCacheProperties;
import com.auction.backend.auction.mapper.AuctionBidRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

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
    private final String consumerName;
    private final AtomicLong lastRedisWarningAt = new AtomicLong(0L);

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
        this.consumerName = buildConsumerName();
    }

    @Scheduled(fixedDelayString = "${auction.cache.redis.hot-bid-stream-poll-interval-ms:100}")
    public void consumePendingEvents() {
        consumeBatch(
                auctionCacheProperties.getHotBidStreamBatchSize(),
                auctionCacheProperties.getHotBidStreamPendingTimeout()
        );
    }

    public boolean catchUpRoom(String roomId, long targetVersion) {
        if (roomId == null || roomId.isBlank() || targetVersion <= 0) {
            return true;
        }

        for (int attempt = 0; attempt < MAX_CATCH_UP_BATCHES; attempt++) {
            if (currentPersistedVersion(roomId) >= targetVersion) {
                return true;
            }
            int processed = consumeBatch(
                    auctionCacheProperties.getHotBidStreamBatchSize(),
                    Duration.ZERO
            );
            if (processed == 0) {
                break;
            }
        }
        return currentPersistedVersion(roomId) >= targetVersion;
    }

    public HotBidStreamStatus status() {
        try {
            ensureGroup();
            StreamBacklog backlog = readStreamBacklog();
            return new HotBidStreamStatus(
                    auctionCacheProperties.getHotBidStreamGroup(),
                    consumerName,
                    backlog.lastDeliveredId(),
                    backlog.streamSize(),
                    backlog.pendingCount(),
                    backlog.lag(),
                    backlog.totalBacklog(),
                    Boolean.TRUE.equals(stringRedisTemplate.hasKey(
                            auctionCacheProperties.getHotBidBackpressureKey()
                    ))
            );
        } catch (RuntimeException exception) {
            log.warn("Failed to read hot bid stream status", exception);
            return new HotBidStreamStatus(
                    auctionCacheProperties.getHotBidStreamGroup(),
                    consumerName,
                    null,
                    0L,
                    0L,
                    0L,
                    0L,
                    false
            );
        }
    }

    public synchronized int consumeBatch(int batchSize, Duration pendingTimeout) {
        if (!ensureGroup()) {
            return 0;
        }
        updateBackpressureState();

        List<MapRecord<String, Object, Object>> records = readNewRecords(batchSize);
        List<MapRecord<String, Object, Object>> pendingRecords = claimPendingRecords(
                batchSize,
                pendingTimeout == null ? Duration.ZERO : pendingTimeout
        );

        Map<RecordId, MapRecord<String, Object, Object>> uniqueRecords = new LinkedHashMap<>();
        records.forEach(record -> uniqueRecords.put(record.getId(), record));
        pendingRecords.forEach(record -> uniqueRecords.put(record.getId(), record));

        if (uniqueRecords.isEmpty()) {
            return 0;
        }

        return processRecords(new ArrayList<>(uniqueRecords.values()));
    }

    private List<MapRecord<String, Object, Object>> readNewRecords(int batchSize) {
        try {
            return stringRedisTemplate.opsForStream().read(
                    Consumer.from(
                            auctionCacheProperties.getHotBidStreamGroup(),
                            consumerName
                    ),
                    StreamReadOptions.empty().count(batchSize),
                    StreamOffset.create(
                            auctionCacheProperties.getHotBidStreamKey(),
                            ReadOffset.lastConsumed()
                    )
            );
        } catch (RuntimeException exception) {
            warnThrottled("Failed to read new hot bid stream events", exception);
            return List.of();
        }
    }

    private List<MapRecord<String, Object, Object>> claimPendingRecords(int batchSize,
                                                                       Duration pendingTimeout) {
        try {
            PendingMessages pendingMessages = stringRedisTemplate.opsForStream().pending(
                    auctionCacheProperties.getHotBidStreamKey(),
                    auctionCacheProperties.getHotBidStreamGroup(),
                    Range.unbounded(),
                    batchSize,
                    pendingTimeout
            );
            if (pendingMessages == null || pendingMessages.isEmpty()) {
                return List.of();
            }

            RecordId[] recordIds = pendingMessages.stream()
                    .map(PendingMessage::getId)
                    .toArray(RecordId[]::new);
            return stringRedisTemplate.opsForStream().claim(
                    auctionCacheProperties.getHotBidStreamKey(),
                    auctionCacheProperties.getHotBidStreamGroup(),
                    consumerName,
                    pendingTimeout,
                    recordIds
            );
        } catch (RuntimeException exception) {
            warnThrottled("Failed to claim pending hot bid stream events", exception);
            return List.of();
        }
    }

    private int processRecords(List<MapRecord<String, Object, Object>> records) {
        List<PreparedEvent> preparedEvents = new ArrayList<>();
        for (MapRecord<String, Object, Object> record : records) {
            PreparedEvent preparedEvent = prepareEvent(record);
            if (preparedEvent != null) {
                preparedEvents.add(preparedEvent);
            }
        }

        if (preparedEvents.isEmpty()) {
            return 0;
        }

        try {
            List<HotBidPersistenceMessage> messages = preparedEvents.stream()
                    .map(PreparedEvent::message)
                    .toList();
            messages.forEach(hotBidPersistenceLogService::markProcessing);
            hotBidPersistenceStore.persistBatch(messages);
            messages.forEach(hotBidPersistenceLogService::markSuccess);
            preparedEvents.forEach(event -> acknowledge(event.recordId()));
            return preparedEvents.size();
        } catch (RuntimeException batchException) {
            log.warn("Hot bid batch persistence failed, retrying events individually", batchException);
        }

        int processed = 0;
        for (PreparedEvent preparedEvent : preparedEvents) {
            if (processSingleEvent(preparedEvent)) {
                processed++;
            }
        }
        return processed;
    }

    private PreparedEvent prepareEvent(MapRecord<String, Object, Object> record) {
        Object rawPayload = record.getValue().get("payload");
        if (rawPayload == null || rawPayload.toString().isBlank()) {
            moveToDeadLetter(record.getId().getValue(), "", "missing payload");
            acknowledge(record.getId());
            return null;
        }

        String payload = rawPayload.toString();
        try {
            HotBidPersistenceMessage message = jsonMapper.readValue(
                    payload,
                    HotBidPersistenceEventPayload.class
            ).toMessage();
            return new PreparedEvent(record.getId(), message);
        } catch (Exception exception) {
            log.error("Failed to parse hot bid stream event {}", record.getId(), exception);
            moveToDeadLetter(record.getId().getValue(), payload, exception.getMessage());
            acknowledge(record.getId());
            return null;
        }
    }

    private boolean processSingleEvent(PreparedEvent preparedEvent) {
        try {
            hotBidPersistenceLogService.markProcessing(preparedEvent.message());
            hotBidPersistenceStore.persist(preparedEvent.message());
            hotBidPersistenceLogService.markSuccess(preparedEvent.message());
            acknowledge(preparedEvent.recordId());
            return true;
        } catch (RuntimeException exception) {
            log.error("Failed to persist hot bid stream event {}", preparedEvent.recordId(), exception);
            try {
                hotBidPersistenceLogService.markFailed(preparedEvent.message(), exception);
            } catch (RuntimeException ignored) {
                // Leave the message pending so the next claim can retry it.
            }
            return false;
        }
    }

    private boolean ensureGroup() {
        String streamKey = auctionCacheProperties.getHotBidStreamKey();
        String groupName = auctionCacheProperties.getHotBidStreamGroup();
        try {
            if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(streamKey))) {
                return false;
            }
            stringRedisTemplate.opsForStream().createGroup(
                    streamKey,
                    ReadOffset.from("0-0"),
                    groupName
            );
            return true;
        } catch (RuntimeException exception) {
            if (isBusyGroup(exception)) {
                return true;
            }
            warnThrottled("Failed to ensure hot bid stream consumer group", exception);
            return false;
        }
    }

    private void warnThrottled(String message, RuntimeException exception) {
        long now = System.currentTimeMillis();
        long previous = lastRedisWarningAt.get();
        if (now - previous >= 30_000 && lastRedisWarningAt.compareAndSet(previous, now)) {
            log.warn(message, exception);
        }
    }

    private boolean isBusyGroup(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current.getClass().getSimpleName().contains("RedisBusyException")
                    || current.getMessage() != null && current.getMessage().contains("BUSYGROUP")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    void updateBackpressureState() {
        try {
            StreamBacklog backlog = readStreamBacklog();
            if (backlog.totalBacklog() > auctionCacheProperties.getHotBidStreamMaxBacklog()) {
                stringRedisTemplate.opsForValue().set(
                        auctionCacheProperties.getHotBidBackpressureKey(),
                        "total=" + backlog.totalBacklog()
                                + ",pending=" + backlog.pendingCount()
                                + ",lag=" + backlog.lag()
                );
            } else {
                stringRedisTemplate.delete(auctionCacheProperties.getHotBidBackpressureKey());
            }
        } catch (RuntimeException exception) {
            warnThrottled("Failed to update hot bid backpressure state", exception);
        }
    }

    private StreamBacklog readStreamBacklog() {
        Long streamSize = stringRedisTemplate.opsForStream()
                .size(auctionCacheProperties.getHotBidStreamKey());
        StreamInfo.XInfoGroup group = findStreamGroup();
        long pendingCount = group == null || group.pendingCount() == null
                ? 0L
                : group.pendingCount();
        long lag = extractLag(group);
        return new StreamBacklog(
                group == null ? null : group.lastDeliveredId(),
                streamSize == null ? 0L : streamSize,
                pendingCount,
                lag
        );
    }

    private StreamInfo.XInfoGroup findStreamGroup() {
        StreamInfo.XInfoGroups groups = stringRedisTemplate.opsForStream()
                .groups(auctionCacheProperties.getHotBidStreamKey());
        if (groups == null) {
            return null;
        }
        return groups.stream()
                .filter(group -> auctionCacheProperties.getHotBidStreamGroup()
                        .equals(group.groupName()))
                .findFirst()
                .orElse(null);
    }

    private long extractLag(StreamInfo.XInfoGroup group) {
        if (group == null || group.getRaw() == null) {
            return 0L;
        }
        Object rawLag = group.getRaw().get("lag");
        if (rawLag instanceof Number number) {
            return number.longValue();
        }
        if (rawLag == null || rawLag.toString().isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(rawLag.toString());
        } catch (NumberFormatException exception) {
            log.warn("Failed to parse hot bid stream lag value {}", rawLag);
            return 0L;
        }
    }

    private void acknowledge(RecordId recordId) {
        try {
            stringRedisTemplate.opsForStream().acknowledge(
                    auctionCacheProperties.getHotBidStreamKey(),
                    auctionCacheProperties.getHotBidStreamGroup(),
                    recordId
            );
        } catch (RuntimeException exception) {
            log.warn("Failed to acknowledge hot bid stream event {}", recordId, exception);
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

    private long currentPersistedVersion(String roomId) {
        Long version = auctionBidRecordMapper.findMaxVersionByRoomId(roomId);
        return version == null ? 0L : version;
    }

    private String buildConsumerName() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + ProcessHandle.current().pid();
        } catch (Exception exception) {
            return "auction-backend-" + ProcessHandle.current().pid();
        }
    }

    private record PreparedEvent(RecordId recordId, HotBidPersistenceMessage message) {
    }

    private record StreamBacklog(String lastDeliveredId,
                                 long streamSize,
                                 long pendingCount,
                                 long lag) {

        long totalBacklog() {
            return pendingCount + lag;
        }
    }
}

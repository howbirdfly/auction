package com.auction.backend.auction.service;

import com.auction.backend.auction.mapper.AuctionBidPersistenceLogMapper;
import com.auction.backend.auction.mapper.AuctionBidRecordMapper;
import com.auction.backend.auction.dto.HotBidReplayResult;
import com.auction.backend.auction.model.AuctionBidPersistenceLog;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

@Service
public class HotBidPersistenceCompensationService {

    private static final int COMPENSATION_BATCH_SIZE = 20;
    private static final int ROOM_CATCH_UP_BATCH_SIZE = 200;
    private static final int PROCESSING_STALE_SECONDS = 10;

    private final AuctionBidPersistenceLogMapper auctionBidPersistenceLogMapper;
    private final AuctionBidRecordMapper auctionBidRecordMapper;
    private final HotBidPersistenceStore hotBidPersistenceStore;
    private final HotBidPersistenceLogService hotBidPersistenceLogService;
    private final JsonMapper jsonMapper;

    public HotBidPersistenceCompensationService(AuctionBidPersistenceLogMapper auctionBidPersistenceLogMapper,
                                                AuctionBidRecordMapper auctionBidRecordMapper,
                                                HotBidPersistenceStore hotBidPersistenceStore,
                                                HotBidPersistenceLogService hotBidPersistenceLogService,
                                                JsonMapper jsonMapper) {
        this.auctionBidPersistenceLogMapper = auctionBidPersistenceLogMapper;
        this.auctionBidRecordMapper = auctionBidRecordMapper;
        this.hotBidPersistenceStore = hotBidPersistenceStore;
        this.hotBidPersistenceLogService = hotBidPersistenceLogService;
        this.jsonMapper = jsonMapper;
    }

    @Scheduled(fixedDelay = 5000)
    public void compensate() {
        List<AuctionBidPersistenceLog> pendingLogs = auctionBidPersistenceLogMapper.findPendingForCompensation(
                Instant.now().minusSeconds(PROCESSING_STALE_SECONDS),
                COMPENSATION_BATCH_SIZE
        );

        for (AuctionBidPersistenceLog pendingLog : pendingLogs) {
            replayOne(pendingLog);
        }
    }

    public HotBidReplayResult replayEvent(String eventId) {
        AuctionBidPersistenceLog log = auctionBidPersistenceLogMapper.findByEventId(eventId);
        if (log == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "hot bid persistence event not found");
        }

        ReplayCounter counter = new ReplayCounter();
        counter.scanned++;
        replayOne(log, counter);
        return toResult("EVENT", eventId, log.getRoomId(), counter);
    }

    public HotBidReplayResult replayPending() {
        List<AuctionBidPersistenceLog> pendingLogs = auctionBidPersistenceLogMapper.findPendingForCompensation(
                Instant.now().minusSeconds(PROCESSING_STALE_SECONDS),
                ROOM_CATCH_UP_BATCH_SIZE
        );

        ReplayCounter counter = new ReplayCounter();
        for (AuctionBidPersistenceLog pendingLog : pendingLogs) {
            counter.scanned++;
            replayOne(pendingLog, counter);
        }

        return toResult("PENDING", "ALL", null, counter);
    }

    public HotBidReplayResult replayRoom(String roomId) {
        List<AuctionBidPersistenceLog> replayableLogs = auctionBidPersistenceLogMapper.findReplayableByRoomId(
                roomId,
                Instant.now().minusSeconds(PROCESSING_STALE_SECONDS),
                ROOM_CATCH_UP_BATCH_SIZE
        );

        ReplayCounter counter = new ReplayCounter();
        for (AuctionBidPersistenceLog replayableLog : replayableLogs) {
            counter.scanned++;
            replayOne(replayableLog, counter);
        }

        return toResult("ROOM", roomId, roomId, counter);
    }

    public boolean catchUpRoom(String roomId, long targetVersion) {
        if (targetVersion <= 0 || currentPersistedVersion(roomId) >= targetVersion) {
            return true;
        }

        List<AuctionBidPersistenceLog> replayableLogs = auctionBidPersistenceLogMapper.findReplayableByRoomId(
                roomId,
                Instant.now().minusSeconds(PROCESSING_STALE_SECONDS),
                ROOM_CATCH_UP_BATCH_SIZE
        );

        for (AuctionBidPersistenceLog replayableLog : replayableLogs) {
            replayOne(replayableLog);
        }

        return currentPersistedVersion(roomId) >= targetVersion;
    }

    private void replayOne(AuctionBidPersistenceLog pendingLog) {
        replayOne(pendingLog, null);
    }

    private void replayOne(AuctionBidPersistenceLog pendingLog, ReplayCounter counter) {
        try {
            if (auctionBidRecordMapper.findByEventId(pendingLog.getEventId()) != null
                    || (pendingLog.getRequestId() != null && auctionBidRecordMapper.findByRequestId(pendingLog.getRequestId()) != null)) {
                hotBidPersistenceLogService.markSuccess(toMessage(pendingLog));
                if (counter != null) {
                    counter.alreadyPersisted++;
                }
                return;
            }

            HotBidPersistenceMessage message = toMessage(pendingLog);
            hotBidPersistenceLogService.markProcessing(message);
            hotBidPersistenceStore.persist(message);
            hotBidPersistenceLogService.markSuccess(message);
            if (counter != null) {
                counter.replayed++;
            }
        } catch (Exception exception) {
            try {
                hotBidPersistenceLogService.markFailed(toMessage(pendingLog), exception);
            } catch (Exception ignored) {
                // Keep retrying on the next schedule.
            }
            if (counter != null) {
                counter.failed++;
            }
        }
    }

    private HotBidPersistenceMessage toMessage(AuctionBidPersistenceLog pendingLog) {
        try {
            if (pendingLog.getPayloadJson() != null && !pendingLog.getPayloadJson().isBlank()) {
                return jsonMapper.readValue(pendingLog.getPayloadJson(), HotBidPersistenceMessage.class);
            }
        } catch (Exception ignored) {
            // Fall back to a minimal message below.
        }

        throw new IllegalStateException("hot bid persistence payload is missing for event " + pendingLog.getEventId());
    }

    private long currentPersistedVersion(String roomId) {
        if (roomId == null || roomId.isBlank()) {
            return 0L;
        }
        Long version = auctionBidRecordMapper.findMaxVersionByRoomId(roomId);
        return version == null ? 0L : version;
    }

    private HotBidReplayResult toResult(String scope, String target, String roomId, ReplayCounter counter) {
        return new HotBidReplayResult(
                scope,
                target,
                counter.scanned,
                counter.replayed,
                counter.alreadyPersisted,
                counter.failed,
                currentPersistedVersion(roomId)
        );
    }

    private static class ReplayCounter {
        private int scanned;
        private int replayed;
        private int alreadyPersisted;
        private int failed;
    }
}

package com.auction.backend.auction.service;

import com.auction.backend.auction.mapper.AuctionBidPersistenceLogMapper;
import com.auction.backend.auction.mapper.AuctionBidRecordMapper;
import com.auction.backend.auction.model.AuctionBidPersistenceLog;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
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
            tryReplay(pendingLog);
        }
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
            tryReplay(replayableLog);
        }

        return currentPersistedVersion(roomId) >= targetVersion;
    }

    private void tryReplay(AuctionBidPersistenceLog pendingLog) {
        try {
            if (auctionBidRecordMapper.findByEventId(pendingLog.getEventId()) != null
                    || (pendingLog.getRequestId() != null && auctionBidRecordMapper.findByRequestId(pendingLog.getRequestId()) != null)) {
                hotBidPersistenceLogService.markSuccess(toMessage(pendingLog));
                return;
            }

            HotBidPersistenceMessage message = toMessage(pendingLog);
            hotBidPersistenceLogService.markProcessing(message);
            hotBidPersistenceStore.persist(message);
            hotBidPersistenceLogService.markSuccess(message);
        } catch (Exception exception) {
            try {
                hotBidPersistenceLogService.markFailed(toMessage(pendingLog), exception);
            } catch (Exception ignored) {
                // Keep retrying on the next schedule.
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
        Long version = auctionBidRecordMapper.findMaxVersionByRoomId(roomId);
        return version == null ? 0L : version;
    }
}

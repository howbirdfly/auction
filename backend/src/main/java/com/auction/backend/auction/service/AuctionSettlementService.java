package com.auction.backend.auction.service;

import com.auction.backend.auction.mapper.AuctionRoomRegistrationMapper;
import com.auction.backend.auction.mapper.AuctionSettlementLogMapper;
import com.auction.backend.auction.model.AuctionRegistrationStatus;
import com.auction.backend.auction.model.AuctionRoom;
import com.auction.backend.auction.model.AuctionRoomRegistration;
import com.auction.backend.auction.model.AuctionSettlementLog;
import com.auction.backend.auction.model.AuctionStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class AuctionSettlementService {

    private static final int MAX_ERROR_LENGTH = 255;
    private static final int COMPENSATION_BATCH_SIZE = 20;
    private static final int PROCESSING_STALE_SECONDS = 10;

    private final AuctionSettlementLogMapper auctionSettlementLogMapper;
    private final AuctionRoomRegistrationMapper auctionRoomRegistrationMapper;
    private final AuctionWalletService auctionWalletService;
    private final AuctionRoomReadService auctionRoomReadService;
    private final HotRoomSettlementCatchUpService hotRoomSettlementCatchUpService;
    private final ObjectProvider<AuctionSettlementService> selfProvider;

    public AuctionSettlementService(AuctionSettlementLogMapper auctionSettlementLogMapper,
                                    AuctionRoomRegistrationMapper auctionRoomRegistrationMapper,
                                    AuctionWalletService auctionWalletService,
                                    AuctionRoomReadService auctionRoomReadService,
                                    HotRoomSettlementCatchUpService hotRoomSettlementCatchUpService,
                                    ObjectProvider<AuctionSettlementService> selfProvider) {
        this.auctionSettlementLogMapper = auctionSettlementLogMapper;
        this.auctionRoomRegistrationMapper = auctionRoomRegistrationMapper;
        this.auctionWalletService = auctionWalletService;
        this.auctionRoomReadService = auctionRoomReadService;
        this.hotRoomSettlementCatchUpService = hotRoomSettlementCatchUpService;
        this.selfProvider = selfProvider;
    }

    @Transactional
    public void settle(AuctionRoom room) {
        if (room.getStatus() != AuctionStatus.CLOSED) {
            return;
        }

        AuctionSettlementLog log = ensureLog(room);
        if ("SUCCESS".equals(log.getStatus())) {
            return;
        }

        if (!hotRoomSettlementCatchUpService.isReadyForSettlement(room)) {
            return;
        }

        if (!acquireSettlementLock(room)) {
            return;
        }

        try {
            List<AuctionRoomRegistration> registrations = auctionRoomRegistrationMapper.findAllByRoomId(room.getRoomId());
            auctionWalletService.syncAccountsToMySql(registrations.stream()
                    .map(AuctionRoomRegistration::getUserId)
                    .distinct()
                    .toList());

            if (!log.isWinnerFundsSettled() && shouldConsumeWinnerFunds(room, registrations)) {
                auctionWalletService.consumeWinningBidOnSettlement(
                        room.getLeaderUserId(),
                        room.getCurrentPrice(),
                        room.getRoomId()
                );
                auctionSettlementLogMapper.markWinnerFundsSettled(room.getRoomId(), Instant.now());
                log.setWinnerFundsSettled(true);
            }

            releaseLockedDeposits(registrations);
            auctionSettlementLogMapper.markDepositsReleased(room.getRoomId(), Instant.now());
            auctionSettlementLogMapper.markSuccess(room.getRoomId(), Instant.now(), Instant.now());
        } catch (RuntimeException exception) {
            auctionSettlementLogMapper.markFailed(room.getRoomId(), truncateError(exception.getMessage()), Instant.now());
        }
    }

    public void compensate() {
        Set<String> processedRoomIds = new HashSet<>();

        List<AuctionSettlementLog> pendingLogs = auctionSettlementLogMapper.findPendingForCompensation(
                Instant.now().minusSeconds(PROCESSING_STALE_SECONDS),
                COMPENSATION_BATCH_SIZE
        );
        for (AuctionSettlementLog log : pendingLogs) {
            processedRoomIds.add(log.getRoomId());
            trySettle(log.getRoomId());
        }

        for (String roomId : auctionRoomRegistrationMapper.findClosedRoomIdsWithLockedRegistrations()) {
            if (processedRoomIds.contains(roomId)) {
                continue;
            }
            trySettle(roomId);
        }
    }

    private AuctionSettlementLog ensureLog(AuctionRoom room) {
        AuctionSettlementLog log = auctionSettlementLogMapper.findByRoomId(room.getRoomId());
        if (log != null) {
            return log;
        }

        Instant now = Instant.now();
        AuctionSettlementLog newLog = new AuctionSettlementLog();
        newLog.setRoomId(room.getRoomId());
        newLog.setWinnerUserId(room.getLeaderUserId());
        newLog.setFinalPrice(room.getCurrentPrice());
        newLog.setStatus("PENDING");
        newLog.setWinnerFundsSettled(false);
        newLog.setDepositsReleased(false);
        newLog.setAttemptCount(0);
        newLog.setCreatedAt(now);
        newLog.setUpdatedAt(now);
        try {
            auctionSettlementLogMapper.insert(newLog);
        } catch (DuplicateKeyException ignored) {
            return auctionSettlementLogMapper.findByRoomId(room.getRoomId());
        }
        return newLog;
    }

    private boolean acquireSettlementLock(AuctionRoom room) {
        Instant now = Instant.now();
        int updatedRows = auctionSettlementLogMapper.markProcessing(
                room.getRoomId(),
                room.getLeaderUserId(),
                room.getCurrentPrice(),
                now,
                now.minusSeconds(PROCESSING_STALE_SECONDS)
        );
        return updatedRows > 0;
    }

    private boolean shouldConsumeWinnerFunds(AuctionRoom room, List<AuctionRoomRegistration> registrations) {
        return room.getLeaderUserId() != null
                && !room.getLeaderUserId().isBlank()
                && safeAmount(room.getCurrentPrice()).compareTo(BigDecimal.ZERO) > 0
                && registrations.stream().anyMatch(registration ->
                room.getLeaderUserId().equals(registration.getUserId())
                        && registration.getStatus() == AuctionRegistrationStatus.LOCKED
        );
    }

    private void releaseLockedDeposits(List<AuctionRoomRegistration> registrations) {
        Instant now = Instant.now();
        for (AuctionRoomRegistration registration : registrations) {
            if (registration.getStatus() != AuctionRegistrationStatus.LOCKED) {
                continue;
            }

            auctionWalletService.releaseDepositOnSettlement(
                    registration.getUserId(),
                    registration.getDepositAmount(),
                    registration.getRoomId()
            );
            registration.setStatus(AuctionRegistrationStatus.RELEASED);
            registration.setUpdatedAt(now);
            auctionRoomRegistrationMapper.updateForRegistration(registration);
        }
    }

    private void trySettle(String roomId) {
        try {
            selfProvider.getObject().settle(auctionRoomReadService.findRoom(roomId));
        } catch (RuntimeException ignored) {
            // Keep compensation idempotent and continue retrying on the next schedule.
        }
    }

    private BigDecimal safeAmount(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }

    private String truncateError(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return "结算失败";
        }
        return errorMessage.length() <= MAX_ERROR_LENGTH
                ? errorMessage
                : errorMessage.substring(0, MAX_ERROR_LENGTH);
    }
}

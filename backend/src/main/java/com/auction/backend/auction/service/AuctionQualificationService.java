package com.auction.backend.auction.service;

import com.auction.backend.auction.dto.AuctionQualificationSnapshot;
import com.auction.backend.auction.dto.AuctionRegistrationRequest;
import com.auction.backend.auction.dto.AuctionRegistrationSnapshot;
import com.auction.backend.auction.mapper.AuctionRoomRegistrationMapper;
import com.auction.backend.auction.model.AuctionRegistrationStatus;
import com.auction.backend.auction.model.AuctionRoom;
import com.auction.backend.auction.model.AuctionRoomRegistration;
import com.auction.backend.auction.model.AuctionStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;

@Service
public class AuctionQualificationService {

    private static final BigDecimal DEFAULT_DEPOSIT_AMOUNT = BigDecimal.valueOf(99);

    private final AuctionRoomReadService auctionRoomReadService;
    private final AuctionRoomRegistrationMapper auctionRoomRegistrationMapper;
    private final AuctionWalletService auctionWalletService;

    public AuctionQualificationService(AuctionRoomReadService auctionRoomReadService,
                                       AuctionRoomRegistrationMapper auctionRoomRegistrationMapper,
                                       AuctionWalletService auctionWalletService) {
        this.auctionRoomReadService = auctionRoomReadService;
        this.auctionRoomRegistrationMapper = auctionRoomRegistrationMapper;
        this.auctionWalletService = auctionWalletService;
    }

    @Transactional(readOnly = true)
    public AuctionQualificationSnapshot getQualification(String roomId, String userId) {
        AuctionRoom room = auctionRoomReadService.findRoom(roomId);
        return toQualification(room, findRegistration(roomId, userId));
    }

    @Transactional
    public AuctionRegistrationSnapshot register(String roomId, AuctionRegistrationRequest request) {
        AuctionRoom room = auctionRoomReadService.findRoom(roomId);
        if (room.getStatus() == AuctionStatus.CLOSED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前房间已结束，不能再报名竞拍");
        }

        if (!room.isRegistrationRequired() && room.getDepositAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前房间未开启报名资格校验");
        }

        Instant now = Instant.now();
        BigDecimal depositAmount = resolveDepositAmount(room);
        AuctionRoomRegistration existing = findRegistration(roomId, request.userId());

        if (existing == null) {
            auctionWalletService.lockDeposit(request.userId().trim(), depositAmount, roomId);
            AuctionRoomRegistration registration = new AuctionRoomRegistration(
                    roomId,
                    request.userId().trim(),
                    request.nickname().trim(),
                    depositAmount,
                    AuctionRegistrationStatus.LOCKED,
                    now,
                    now
            );
            auctionRoomRegistrationMapper.insert(registration);
            return toSnapshot(registration);
        }

        BigDecimal previousDepositAmount = existing.getStatus() == AuctionRegistrationStatus.LOCKED
                ? existing.getDepositAmount()
                : BigDecimal.ZERO;
        BigDecimal depositDelta = depositAmount.subtract(previousDepositAmount);
        if (existing.getStatus() != AuctionRegistrationStatus.LOCKED) {
            auctionWalletService.lockDeposit(request.userId().trim(), depositAmount, roomId);
        } else if (depositDelta.compareTo(BigDecimal.ZERO) > 0) {
            auctionWalletService.lockDeposit(request.userId().trim(), depositDelta, roomId);
        } else if (depositDelta.compareTo(BigDecimal.ZERO) < 0) {
            auctionWalletService.releaseDeposit(request.userId().trim(), depositDelta.abs(), roomId);
        }

        existing.setNickname(request.nickname().trim());
        existing.setDepositAmount(depositAmount);
        existing.setStatus(AuctionRegistrationStatus.LOCKED);
        existing.setUpdatedAt(now);
        auctionRoomRegistrationMapper.updateForRegistration(existing);
        return toSnapshot(existing);
    }

    public void assertEligibleToBid(AuctionRoom room, String userId) {
        if (!room.isRegistrationRequired() && room.getDepositAmount().compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        AuctionRoomRegistration registration = findRegistration(room.getRoomId(), userId);
        if (registration == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先报名竞拍并冻结保证金");
        }

        if (registration.getStatus() != AuctionRegistrationStatus.LOCKED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前账号的保证金状态无效，暂时不能出价");
        }
    }

    public boolean resolveRegistrationRequired(Boolean requested) {
        return requested == null || requested;
    }

    public BigDecimal resolveDepositAmount(BigDecimal requested, boolean registrationRequired) {
        if (!registrationRequired) {
            return BigDecimal.ZERO;
        }
        if (requested == null || requested.compareTo(BigDecimal.ZERO) <= 0) {
            return DEFAULT_DEPOSIT_AMOUNT;
        }
        return requested;
    }

    private AuctionQualificationSnapshot toQualification(AuctionRoom room, AuctionRoomRegistration registration) {
        boolean registered = registration != null;
        boolean canBid = !room.isRegistrationRequired() && room.getDepositAmount().compareTo(BigDecimal.ZERO) <= 0
                || registered && registration.getStatus() == AuctionRegistrationStatus.LOCKED;

        String status = registration == null ? "UNREGISTERED" : registration.getStatus().name();
        String message = canBid
                ? "已获得竞拍资格，可以直接出价"
                : "请先报名竞拍并冻结保证金，再参与出价";

        return new AuctionQualificationSnapshot(
                room.isRegistrationRequired(),
                resolveDepositAmount(room),
                registered,
                canBid,
                status,
                message
        );
    }

    private BigDecimal resolveDepositAmount(AuctionRoom room) {
        return room.getDepositAmount() == null ? BigDecimal.ZERO : room.getDepositAmount();
    }

    private AuctionRoomRegistration findRegistration(String roomId, String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        return auctionRoomRegistrationMapper.findByRoomAndUser(roomId, userId.trim());
    }

    private AuctionRegistrationSnapshot toSnapshot(AuctionRoomRegistration registration) {
        return new AuctionRegistrationSnapshot(
                registration.getRoomId(),
                registration.getUserId(),
                registration.getNickname(),
                registration.getDepositAmount(),
                registration.getStatus().name(),
                registration.getCreatedAt(),
                registration.getUpdatedAt()
        );
    }
}

package com.auction.backend.user.service;

import com.auction.backend.auction.dto.AuctionRoomSnapshot;
import com.auction.backend.auction.mapper.AuctionRoomRegistrationMapper;
import com.auction.backend.auction.model.AuctionRoom;
import com.auction.backend.auction.model.AuctionRoomRegistration;
import com.auction.backend.auction.model.AuctionStatus;
import com.auction.backend.auction.service.AuctionRoomReadService;
import com.auction.backend.user.dto.UserAuctionHistorySnapshot;
import com.auction.backend.user.mapper.UserAccountMapper;
import com.auction.backend.user.model.UserAccount;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class UserAuctionHistoryService {

    private final UserAccountMapper userAccountMapper;
    private final AuctionRoomRegistrationMapper auctionRoomRegistrationMapper;
    private final AuctionRoomReadService auctionRoomReadService;

    public UserAuctionHistoryService(UserAccountMapper userAccountMapper,
                                     AuctionRoomRegistrationMapper auctionRoomRegistrationMapper,
                                     AuctionRoomReadService auctionRoomReadService) {
        this.userAccountMapper = userAccountMapper;
        this.auctionRoomRegistrationMapper = auctionRoomRegistrationMapper;
        this.auctionRoomReadService = auctionRoomReadService;
    }

    @Transactional(readOnly = true)
    public UserAuctionHistorySnapshot getHistory(String userId) {
        UserAccount userAccount = userAccountMapper.findById(userId);
        if (userAccount == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found");
        }

        String account = userAccount.getAccount();
        String nickname = userAccount.getNickname();

        List<RoomHistoryEntry> registeredEntries = loadHistoryEntries(
                auctionRoomRegistrationMapper.findAllByUserId(account).stream()
                        .map(AuctionRoomRegistration::getRoomId)
                        .toList()
        );

        List<AuctionRoomSnapshot> registeredRooms = registeredEntries.stream()
                .map(RoomHistoryEntry::snapshot)
                .toList();

        List<AuctionRoomSnapshot> wonRooms = registeredEntries.stream()
                .filter(entry -> entry.room().getStatus() == AuctionStatus.CLOSED)
                .filter(entry -> account.equals(entry.room().getLeaderUserId()))
                .filter(entry -> entry.room().hasLeader())
                .map(RoomHistoryEntry::snapshot)
                .toList();

        List<AuctionRoomSnapshot> missedRooms = registeredEntries.stream()
                .filter(entry -> entry.room().getStatus() == AuctionStatus.CLOSED)
                .filter(entry -> !account.equals(entry.room().getLeaderUserId()))
                .map(RoomHistoryEntry::snapshot)
                .toList();

        List<AuctionRoomSnapshot> createdClosedRooms = auctionRoomReadService.listRooms().stream()
                .filter(snapshot -> snapshot.status() == AuctionStatus.CLOSED)
                .filter(snapshot -> nickname.equals(snapshot.anchorName()))
                .sorted(Comparator.comparing(AuctionRoomSnapshot::endsAt).reversed())
                .toList();

        return new UserAuctionHistorySnapshot(
                createdClosedRooms,
                registeredRooms,
                wonRooms,
                missedRooms
        );
    }

    private List<RoomHistoryEntry> loadHistoryEntries(List<String> roomIds) {
        Set<String> distinctRoomIds = new LinkedHashSet<>(roomIds);
        return distinctRoomIds.stream()
                .map(this::findRoomHistoryEntry)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing((RoomHistoryEntry entry) -> entry.room().getEndsAt()).reversed())
                .toList();
    }

    private RoomHistoryEntry findRoomHistoryEntry(String roomId) {
        try {
            AuctionRoom room = auctionRoomReadService.findRoom(roomId);
            return new RoomHistoryEntry(room, auctionRoomReadService.toSnapshot(room, false));
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode() == HttpStatus.NOT_FOUND) {
                return null;
            }
            throw exception;
        }
    }

    private record RoomHistoryEntry(AuctionRoom room, AuctionRoomSnapshot snapshot) {
    }
}

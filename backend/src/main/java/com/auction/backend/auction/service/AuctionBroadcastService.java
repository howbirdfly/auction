package com.auction.backend.auction.service;

import com.auction.backend.auction.dto.AuctionLeaderboardEntry;
import com.auction.backend.auction.dto.AuctionLeaderboardSnapshot;
import com.auction.backend.auction.dto.AuctionRoomSnapshot;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AuctionBroadcastService {

    private final SimpMessagingTemplate messagingTemplate;

    public AuctionBroadcastService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void broadcastRoom(AuctionRoomSnapshot snapshot) {
        messagingTemplate.convertAndSend("/topic/auction/" + snapshot.roomId(), snapshot);
    }

    public void broadcastLeaderboard(AuctionRoomSnapshot snapshot, List<AuctionLeaderboardEntry> leaderboard) {
        messagingTemplate.convertAndSend(
                "/topic/auction/" + snapshot.roomId() + "/leaderboard",
                new AuctionLeaderboardSnapshot(snapshot.roomId(), snapshot.version(), leaderboard)
        );
    }

    public void broadcastLobby(List<AuctionRoomSnapshot> snapshots) {
        messagingTemplate.convertAndSend("/topic/auction/lobby", snapshots);
    }
}

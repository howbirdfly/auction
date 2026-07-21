import { Client } from "@stomp/stompjs";

const WS_URL = "ws://localhost:8080/ws-auction";

export function createAuctionSocket({
  onRoomMessage = () => {},
  onLeaderboardMessage = () => {},
} = {}) {
  let activeRoomId = null;
  let roomSubscription = null;
  let leaderboardSubscription = null;

  const client = new Client({
    brokerURL: WS_URL,
    reconnectDelay: 1500,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    onConnect() {
      resubscribeRoomTopics();
    },
    onStompError(frame) {
      console.error("STOMP error", frame.headers["message"], frame.body);
    },
    onWebSocketError(error) {
      console.error("WebSocket error", error);
    },
  });

  client.activate();

  function parseBody(frame) {
    try {
      return JSON.parse(frame.body);
    } catch (error) {
      console.error("Failed to parse WebSocket payload", error);
      return null;
    }
  }

  function unsubscribeRoomTopics() {
    roomSubscription?.unsubscribe();
    leaderboardSubscription?.unsubscribe();
    roomSubscription = null;
    leaderboardSubscription = null;
  }

  function resubscribeRoomTopics() {
    unsubscribeRoomTopics();

    if (!client.connected || !activeRoomId) {
      return;
    }

    roomSubscription = client.subscribe(`/topic/auction/${activeRoomId}`, (frame) => {
      const payload = parseBody(frame);
      if (payload) {
        onRoomMessage(payload);
      }
    });

    leaderboardSubscription = client.subscribe(`/topic/auction/${activeRoomId}/leaderboard`, (frame) => {
      const payload = parseBody(frame);
      if (payload && Array.isArray(payload.leaderboard)) {
        onLeaderboardMessage(activeRoomId, payload);
        return;
      }

      if (Array.isArray(payload)) {
        onLeaderboardMessage(activeRoomId, {
          roomId: activeRoomId,
          version: 0,
          leaderboard: payload,
        });
      }
    });
  }

  return {
    subscribeRoom(roomId) {
      activeRoomId = roomId || null;
      if (client.connected) {
        resubscribeRoomTopics();
      }
    },
    disconnect() {
      activeRoomId = null;
      unsubscribeRoomTopics();
      client.deactivate();
    },
  };
}

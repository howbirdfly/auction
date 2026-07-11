import { Client } from "@stomp/stompjs";

const WS_URL = "ws://localhost:8080/ws-auction";

export function createAuctionSocket({ onLobbyMessage, onRoomMessage }) {
  const subscriptions = [];
  let roomSubscription = null;
  let pendingRoomId = null;

  const client = new Client({
    brokerURL: WS_URL,
    reconnectDelay: 3000,
  });

  client.onConnect = () => {
    subscriptions.push(
      client.subscribe("/topic/auction/lobby", (message) => {
        onLobbyMessage?.(JSON.parse(message.body));
      }),
    );

    if (pendingRoomId) {
      roomSubscription = client.subscribe(`/topic/auction/${pendingRoomId}`, (message) => {
        onRoomMessage?.(JSON.parse(message.body));
      });
    }
  };

  client.activate();

  return {
    subscribeRoom(roomId) {
      pendingRoomId = roomId;

      if (roomSubscription) {
        roomSubscription.unsubscribe();
        roomSubscription = null;
      }

      if (client.connected) {
        roomSubscription = client.subscribe(`/topic/auction/${roomId}`, (message) => {
          onRoomMessage?.(JSON.parse(message.body));
        });
      }
    },
    disconnect() {
      roomSubscription?.unsubscribe();
      subscriptions.forEach((subscription) => subscription.unsubscribe());
      client.deactivate();
    },
  };
}

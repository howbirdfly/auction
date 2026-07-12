export function createAuctionSocket() {
  return {
    subscribeRoom() {
      // Realtime socket is temporarily disabled.
    },
    disconnect() {
      // No-op.
    },
  };
}

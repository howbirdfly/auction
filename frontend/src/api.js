const API_BASE = "http://localhost:8080/api";

async function request(path, options = {}) {
  const response = await fetch(`${API_BASE}${path}`, {
    headers: {
      "Content-Type": "application/json",
      ...(options.headers ?? {}),
    },
    ...options,
  });

  const payload = await response.json();
  if (!response.ok || payload.success === false) {
    throw new Error(payload.message || "请求失败");
  }
  return payload.data;
}

export function fetchRooms() {
  return request("/auctions");
}

export function fetchRoom(roomId) {
  return request(`/auctions/${roomId}`);
}

export function createRoom(form) {
  return request("/auctions", {
    method: "POST",
    body: JSON.stringify(form),
  });
}

export function createBid(roomId, form) {
  return request(`/auctions/${roomId}/bids`, {
    method: "POST",
    body: JSON.stringify(form),
  });
}

export function fetchUsers() {
  return request("/users");
}

export function fetchUser(userId) {
  return request(`/users/${userId}`);
}

export function updateUser(userId, form) {
  return request(`/users/${userId}`, {
    method: "PUT",
    body: JSON.stringify(form),
  });
}

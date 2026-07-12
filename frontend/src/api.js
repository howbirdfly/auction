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

export function createAvatarUploadPolicy(form) {
  return request("/uploads/avatar-policy", {
    method: "POST",
    body: JSON.stringify(form),
  });
}

export async function uploadAvatarToOss(file, uploadPolicy) {
  const formData = new FormData();
  formData.append("key", uploadPolicy.objectKey);
  formData.append("policy", uploadPolicy.policy);
  formData.append("OSSAccessKeyId", uploadPolicy.accessKeyId);
  formData.append("Signature", uploadPolicy.signature);
  formData.append("success_action_status", String(uploadPolicy.successActionStatus));
  formData.append("file", file);

  const response = await fetch(uploadPolicy.host, {
    method: "POST",
    body: formData,
  });

  if (!response.ok) {
    const responseText = await response.text();
    const codeMatch = responseText.match(/<Code>([^<]+)<\/Code>/i);
    const messageMatch = responseText.match(/<Message>([^<]+)<\/Message>/i);
    const errorCode = codeMatch?.[1];
    const errorMessage = messageMatch?.[1];

    if (errorCode || errorMessage) {
      throw new Error(`头像上传失败${errorCode ? `（${errorCode}）` : ""}${errorMessage ? `：${errorMessage}` : ""}`);
    }

    throw new Error("头像上传到 OSS 失败");
  }

  return uploadPolicy.publicUrl;
}

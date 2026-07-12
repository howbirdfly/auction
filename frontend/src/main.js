import "./styles.css";
import {
  createAvatarUploadPolicy,
  createBid,
  createRoom,
  fetchRoom,
  fetchRooms,
  fetchUsers,
  updateUser,
  uploadAvatarToOss,
} from "./api";
import { createAuctionSocket } from "./socket";

const DEFAULT_IMAGE = "https://placehold.co/800x600/f6f7fb/1f2937?text=Auction+Room";
const DEFAULT_AVATAR = "https://placehold.co/256x256/f3f4f6/111827?text=User";
const CURRENT_USER_STORAGE_KEY = "auction-current-user-id";

const CHANNELS = ["关注", "推荐", "新发", "省钱神券", "找服务", "热卖"];
const CATEGORIES = [
  { icon: "🍔", label: "吃喝玩乐" },
  { icon: "📱", label: "手机数码" },
  { icon: "♻️", label: "上门回收" },
  { icon: "🏠", label: "二手好物" },
];

const state = {
  rooms: [],
  users: [],
  currentUserId: localStorage.getItem(CURRENT_USER_STORAGE_KEY) || null,
  currentUser: null,
  selectedRoomId: null,
  selectedRoom: null,
  socket: null,
  activeTab: "home",
  activeChannel: "推荐",
  feedback: "",
  feedbackError: false,
};

const app = document.querySelector("#app");

app.innerHTML = `
  <div class="app-shell">
    <main id="screen" class="screen"></main>
    <section id="feedbackBanner" class="feedback-banner"></section>
    <nav class="bottom-nav">
      <button class="bottom-nav-item active" data-tab="home">
        <span class="bottom-nav-icon">🏠</span>
        <strong>首页</strong>
      </button>
      <button class="bottom-nav-item publish-entry" data-tab="publish">
        <span class="publish-entry-badge">📷</span>
        <strong>发布</strong>
      </button>
      <button class="bottom-nav-item" data-tab="profile">
        <span class="bottom-nav-icon">🙂</span>
        <strong>我的</strong>
      </button>
    </nav>
  </div>
`;

const screenEl = document.querySelector("#screen");
const feedbackBannerEl = document.querySelector("#feedbackBanner");
const bottomNavItems = document.querySelectorAll(".bottom-nav-item");

function formatPrice(value) {
  return `¥${Number(value || 0).toFixed(2)}`;
}

function formatCountdown(seconds) {
  const safe = Math.max(0, Number(seconds || 0));
  const mins = String(Math.floor(safe / 60)).padStart(2, "0");
  const secs = String(safe % 60).padStart(2, "0");
  return `${mins}:${secs}`;
}

function formatShortTime(value) {
  if (!value) {
    return "--";
  }
  return new Date(value).toLocaleDateString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
  });
}

function setFeedback(message, isError = false) {
  state.feedback = message;
  state.feedbackError = isError;
  renderFeedback();
}

function renderFeedback() {
  feedbackBannerEl.textContent = state.feedback;
  feedbackBannerEl.classList.toggle("visible", Boolean(state.feedback));
  feedbackBannerEl.classList.toggle("error", state.feedbackError);
}

function getImageUrl(room) {
  return room.imageUrl || DEFAULT_IMAGE;
}

function getAvatarUrl(user) {
  return user?.avatarUrl || DEFAULT_AVATAR;
}

function getCurrentUser() {
  return state.currentUser;
}

function syncCurrentUser() {
  if (!state.users.length) {
    state.currentUser = null;
    state.currentUserId = null;
    localStorage.removeItem(CURRENT_USER_STORAGE_KEY);
    return;
  }

  const matched =
    state.users.find((user) => user.userId === state.currentUserId) ||
    state.users.find((user) => user.account === "u10001") ||
    state.users[0];

  state.currentUser = matched;
  state.currentUserId = matched.userId;
  localStorage.setItem(CURRENT_USER_STORAGE_KEY, matched.userId);
}

function setCurrentUser(userId) {
  state.currentUserId = userId;
  syncCurrentUser();
  renderPage();
}

function getLiveRooms() {
  return state.rooms.filter((room) => room.status === "BIDDING");
}

function getCurrentUserLeadingRooms() {
  const currentUser = getCurrentUser();
  if (!currentUser) {
    return [];
  }
  return state.rooms.filter((room) => room.leaderNickname === currentUser.nickname);
}

function getCurrentUserCreatedRooms() {
  const currentUser = getCurrentUser();
  if (!currentUser) {
    return [];
  }
  return state.rooms.filter((room) => room.anchorName === currentUser.nickname);
}

function renderHomeHeader() {
  return `
    <section class="home-top">
      <div class="search-row">
        <button class="badge-button" data-placeholder="签到功能后续接入">签到</button>
        <div class="search-shell">
          <span class="search-placeholder">搜索拍品、房间或主播</span>
          <span class="search-actions">📷</span>
          <button class="search-button" data-placeholder="搜索功能后续接入">搜索</button>
        </div>
      </div>

      <div class="channel-tabs">
        ${CHANNELS.map(
          (channel) => `
            <button class="channel-tab ${channel === state.activeChannel ? "active" : ""}" data-channel="${channel}">
              ${channel}
            </button>
          `,
        ).join("")}
      </div>
    </section>
  `;
}

function renderCategoryRow() {
  return `
    <section class="category-row">
      ${CATEGORIES.map(
        (category) => `
          <button class="category-item" data-placeholder="${category.label}分类后续接入">
            <span class="category-icon">${category.icon}</span>
            <span>${category.label}</span>
          </button>
        `,
      ).join("")}
    </section>
  `;
}

function renderPromoBanner() {
  return `
    <section class="promo-banner">
      <div>
        <p>直播竞拍先上拍卖场</p>
        <strong>精选房间低价起拍</strong>
      </div>
      <button class="promo-button" data-placeholder="活动会场后续接入">去围观</button>
    </section>
  `;
}

function renderHomeStats() {
  const liveRooms = state.rooms.filter((room) => room.status === "BIDDING").length;
  const closedRooms = state.rooms.filter((room) => room.status === "CLOSED").length;

  return `
    <section class="home-stats">
      <article class="metric-card">
        <span>竞拍中</span>
        <strong>${liveRooms}</strong>
      </article>
      <article class="metric-card">
        <span>已结束</span>
        <strong>${closedRooms}</strong>
      </article>
      <button class="metric-card metric-action" id="refreshRoomsButton">
        <span>房间列表</span>
        <strong>刷新房间</strong>
      </button>
    </section>
  `;
}

function renderRoomCard(room) {
  return `
    <button class="feed-room-card" data-room-id="${room.roomId}">
      <div class="feed-image-wrap">
        <img
          class="feed-room-image"
          src="${getImageUrl(room)}"
          alt="${room.itemTitle}"
          loading="lazy"
          onerror="this.src='${DEFAULT_IMAGE}'"
        />
        <span class="status-pill ${room.status === "CLOSED" ? "closed" : ""}">
          ${room.status === "CLOSED" ? "已结束" : "竞拍中"}
        </span>
      </div>
      <div class="feed-room-body">
        <h3>${room.itemTitle}</h3>
        <div class="feed-room-price">
          <strong>${formatPrice(room.currentPrice)}</strong>
          <span data-room-countdown="${room.roomId}">${formatCountdown(room.secondsRemaining)}</span>
        </div>
        <p class="feed-room-note">${room.anchorName} · ${room.leaderNickname || "暂无领先者"}</p>
        <div class="feed-room-footer">
          <span>${room.roomId}</span>
          <span>${room.bidCount} 次出价</span>
        </div>
      </div>
    </button>
  `;
}

function renderLobbyView() {
  screenEl.innerHTML = `
    ${renderHomeHeader()}
    ${renderCategoryRow()}
    ${renderPromoBanner()}
    ${renderHomeStats()}

    <section class="feed-grid">
      ${
        state.rooms.length
          ? state.rooms.map(renderRoomCard).join("")
          : `<div class="empty-card">暂时还没有房间，去“发布”里先创建一个拍卖房间吧。</div>`
      }
    </section>
  `;

  screenEl.querySelectorAll("[data-room-id]").forEach((button) => {
    button.addEventListener("click", () => {
      openRoom(button.dataset.roomId);
    });
  });

  screenEl.querySelectorAll("[data-channel]").forEach((button) => {
    button.addEventListener("click", () => {
      state.activeChannel = button.dataset.channel;
      renderPage();
    });
  });

  screenEl.querySelector("#refreshRoomsButton")?.addEventListener("click", async () => {
    try {
      await loadRooms();
      setFeedback("房间列表已刷新");
      renderPage();
    } catch (error) {
      setFeedback(error.message, true);
    }
  });

  bindPlaceholderButtons();
}

function renderBidPanel(room) {
  const currentUser = getCurrentUser();

  if (!currentUser) {
    return `
      <section class="room-panel bid-panel">
        <div class="section-header compact">
          <div>
            <h2>模拟出价</h2>
            <p>请先去“我的”里选择一个当前演示账号，再回来参与出价。</p>
          </div>
        </div>
      </section>
    `;
  }

  return `
    <section class="room-panel bid-panel">
      <div class="section-header compact">
        <div>
          <h2>模拟出价</h2>
          <p>当前会使用你在“我的”里选中的账号直接出价。</p>
        </div>
      </div>

      <div class="bid-user-card">
        <div class="bid-user-head">
          <img class="bid-user-avatar" src="${getAvatarUrl(currentUser)}" alt="${currentUser.nickname}" />
          <div>
            <strong>${currentUser.nickname}</strong>
            <span>@${currentUser.account} · ${currentUser.userId}</span>
          </div>
        </div>
      </div>

      <form id="bidForm" class="stack-form">
        <div class="bid-amount-panel">
          <div class="bid-amount-label">
            <span>本次出价</span>
            <strong>每次加价 ${formatPrice(room.stepPrice)}</strong>
          </div>
          <div class="bid-stepper">
            <button type="button" class="bid-step-button" data-bid-adjust="-1">-</button>
            <input
              id="bidAmountInput"
              name="amount"
              type="number"
              min="0.01"
              step="0.01"
              inputmode="decimal"
              placeholder="本次出价金额"
              value="${Number(room.currentPrice).toFixed(2)}"
              required
            />
            <button type="button" class="bid-step-button" data-bid-adjust="1">+</button>
          </div>
        </div>
        <button type="submit">立即出价</button>
      </form>
    </section>
  `;
}

function renderRoomView() {
  const room = state.selectedRoom;

  if (!room) {
    renderLobbyView();
    return;
  }

  screenEl.innerHTML = `
    <section class="room-screen">
      <header class="room-topbar">
        <button id="backToLobby" class="back-button">返回</button>
        <div class="room-topbar-meta">
          <span class="status-pill inline ${room.status === "CLOSED" ? "closed" : ""}">
            ${room.status === "CLOSED" ? "已结束" : "竞拍中"}
          </span>
          <strong>${room.roomId}</strong>
        </div>
      </header>

      <section class="room-cover-card">
        <img
          class="room-cover-image"
          src="${getImageUrl(room)}"
          alt="${room.itemTitle}"
          onerror="this.src='${DEFAULT_IMAGE}'"
        />
        <div class="room-cover-overlay">
          <p class="eyebrow">Auction Room</p>
          <h1>${room.itemTitle}</h1>
          <p>${room.anchorName}</p>
        </div>
      </section>

      <section class="room-summary">
        <div class="summary-card primary">
          <span>当前价</span>
          <strong>${formatPrice(room.currentPrice)}</strong>
        </div>
        <div class="summary-card">
          <span>下一口起拍</span>
          <strong>${formatPrice(room.minNextBid)}</strong>
        </div>
        <div class="summary-card">
          <span>领先者</span>
          <strong>${room.leaderNickname || "暂无"}</strong>
        </div>
        <div class="summary-card">
          <span>剩余时间</span>
          <strong id="roomCountdownValue">${formatCountdown(room.secondsRemaining)}</strong>
        </div>
      </section>

      <section class="room-panel">
        <div class="section-header compact">
          <div>
            <h2>最新出价记录</h2>
            <p>房间内所有人都能看到这里的实时变化。</p>
          </div>
        </div>
        <div class="timeline">
          ${
            room.recentBids.length
              ? room.recentBids
                  .map(
                    (bid) => `
                      <div class="bid-row">
                        <div>
                          <strong>${bid.nickname}</strong>
                          <span>${new Date(bid.bidTime).toLocaleTimeString("zh-CN")}</span>
                        </div>
                        <b>${formatPrice(bid.amount)}</b>
                      </div>
                    `,
                  )
                  .join("")
              : `<div class="empty-card">还没有用户出价，当前房间正在等待第一位竞拍者。</div>`
          }
        </div>
      </section>

      ${renderBidPanel(room)}
    </section>
  `;

  screenEl.querySelector("#backToLobby")?.addEventListener("click", () => {
    state.selectedRoomId = null;
    state.selectedRoom = null;
    renderPage();
  });

  screenEl.querySelector("#bidForm")?.addEventListener("submit", handleBid);
  bindBidAmountControls(room);
}

function renderPublishView() {
  const currentUser = getCurrentUser();

  screenEl.innerHTML = `
    <section class="publish-screen">
      <section class="publish-hero">
        <p class="eyebrow">CREATE ROOM</p>
        <h1>发布竞拍房间</h1>
        <p class="hero-text">把创建房间单独放在这里，首页负责逛房间，个人页只保留和账号相关的内容。</p>
      </section>

      <section class="room-panel">
        <div class="section-header compact">
          <div>
            <h2>创建拍卖房</h2>
            <p>${currentUser ? `当前默认使用 ${currentUser.nickname} 作为主播名。` : "请先去“我的”选择一个账号后再发布。"}</p>
          </div>
        </div>
        <form id="createForm" class="stack-form">
          <input name="itemTitle" placeholder="拍品名称" required />
          <input name="anchorName" placeholder="主播名称" value="${currentUser?.nickname || ""}" required />
          <input name="imageUrl" placeholder="房间封面图链接（可选）" />
          <input name="startPrice" type="number" min="0.01" step="0.01" placeholder="起拍价" required />
          <input name="stepPrice" type="number" min="0.01" step="0.01" placeholder="加价幅度" required />
          <input name="durationSeconds" type="number" min="30" step="1" placeholder="持续时长（秒）" required />
          <button type="submit">立即发布房间</button>
        </form>
      </section>
    </section>
  `;

  screenEl.querySelector("#createForm")?.addEventListener("submit", handleCreateRoom);
}

function renderProfileView() {
  const currentUser = getCurrentUser();
  const createdRooms = getCurrentUserCreatedRooms();
  const leadingRooms = getCurrentUserLeadingRooms();

  if (!currentUser) {
    screenEl.innerHTML = `
      <section class="profile-screen">
        <section class="profile-card">
          <p class="eyebrow">MY CENTER</p>
          <h1>个人主页</h1>
          <p>后端用户接口已经接好了，但当前还没有可用账号。</p>
        </section>
      </section>
    `;
    return;
  }

  screenEl.innerHTML = `
    <section class="profile-screen">
      <section class="profile-card profile-overview-card">
        <div class="profile-overview-head">
          <div class="profile-mini-logo">A</div>
          <div>
            <h1>个人主页</h1>
            <p class="profile-account">这里只展示账号资料、我创建的房间和我当前领先的竞拍。</p>
          </div>
        </div>

        <div class="profile-overview-actions">
          <label class="profile-account-switcher">
            <span>当前账号</span>
            <select id="currentUserSelect">
              ${state.users
                .map(
                  (user) => `
                    <option value="${user.userId}" ${user.userId === currentUser.userId ? "selected" : ""}>
                      ${user.nickname} · @${user.account}
                    </option>
                  `,
                )
                .join("")}
            </select>
          </label>
          <button class="ghost-button" id="jumpToEditor">编辑资料</button>
        </div>
      </section>

      <section class="profile-card user-profile-card">
        <div class="user-profile-head">
          <div class="profile-avatar-wrap">
            <img class="profile-avatar" src="${getAvatarUrl(currentUser)}" alt="${currentUser.nickname}" />
            <button type="button" class="profile-avatar-button" id="changeAvatarButton">更换头像</button>
            <input id="avatarFileInput" type="file" accept="image/png,image/jpeg,image/webp,image/gif" hidden />
          </div>
          <div>
            <p class="eyebrow">MY AUCTION PROFILE</p>
            <h1>${currentUser.nickname}</h1>
            <p class="profile-account">@${currentUser.account} · ${currentUser.userId}</p>
          </div>
        </div>
        <p class="profile-bio">${currentUser.bio || "这个用户还没有写简介。"}</p>
      </section>

      <section class="profile-metrics">
        <div class="metric-card">
          <span>我创建的房间</span>
          <strong>${createdRooms.length}</strong>
        </div>
        <div class="metric-card">
          <span>当前领先中</span>
          <strong>${leadingRooms.length}</strong>
        </div>
        <div class="metric-card">
          <span>竞拍中房间</span>
          <strong>${getLiveRooms().length}</strong>
        </div>
        <div class="metric-card">
          <span>加入时间</span>
          <strong>${formatShortTime(currentUser.createdAt)}</strong>
        </div>
      </section>

      <section class="room-panel">
        <div class="section-header compact">
          <div>
            <h2>我创建的房间</h2>
            <p>这里会展示你发布过的竞拍房间。</p>
          </div>
        </div>
        <div class="history-room-list">
          ${
            createdRooms.length
              ? createdRooms.map(renderHistoryRoomItem).join("")
              : `<div class="empty-card">你还没有创建房间，去底部“发布”试试吧。</div>`
          }
        </div>
      </section>

      <section class="room-panel">
        <div class="section-header compact">
          <div>
            <h2>我当前领先</h2>
            <p>这里会展示你目前排在第一的竞拍房间。</p>
          </div>
        </div>
        <div class="history-room-list">
          ${
            leadingRooms.length
              ? leadingRooms.map(renderHistoryRoomItem).join("")
              : `<div class="empty-card">你当前还没有领先中的房间。</div>`
          }
        </div>
      </section>

      <section class="room-panel">
        <div id="profileEditorAnchor"></div>
        <div class="section-header compact">
          <div>
            <h2>编辑资料</h2>
            <p>头像建议用上面的按钮直接上传，这里主要修改昵称、简介和密码。</p>
          </div>
        </div>
        <form id="profileForm" class="stack-form">
          <input name="nickname" placeholder="昵称" value="${currentUser.nickname}" required />
          <input name="avatarUrl" placeholder="需要手动替换时再填写新的头像地址" />
          <textarea name="bio" rows="4" placeholder="个人简介">${currentUser.bio || ""}</textarea>
          <input name="password" type="password" placeholder="新密码，不修改可留空" />
          <button type="submit">保存资料</button>
        </form>
      </section>
    </section>
  `;

  screenEl.querySelector("#currentUserSelect")?.addEventListener("change", (event) => {
    setCurrentUser(event.target.value);
  });
  screenEl.querySelector("#changeAvatarButton")?.addEventListener("click", () => {
    screenEl.querySelector("#avatarFileInput")?.click();
  });
  screenEl.querySelector("#avatarFileInput")?.addEventListener("change", handleAvatarSelected);
  screenEl.querySelector("#jumpToEditor")?.addEventListener("click", () => {
    document.querySelector("#profileEditorAnchor")?.scrollIntoView({ behavior: "smooth", block: "start" });
  });
  screenEl.querySelectorAll(".history-room-item[data-room-id]").forEach((button) => {
    button.addEventListener("click", () => {
      state.activeTab = "home";
      openRoom(button.dataset.roomId);
    });
  });
  screenEl.querySelector("#profileForm")?.addEventListener("submit", handleUpdateProfile);
}

function renderHistoryRoomItem(room) {
  return `
    <button class="history-room-item" data-room-id="${room.roomId}">
      <img
        class="history-room-cover"
        src="${getImageUrl(room)}"
        alt="${room.itemTitle}"
        onerror="this.src='${DEFAULT_IMAGE}'"
      />
      <div class="history-room-content">
        <div class="history-room-title-row">
          <strong>${room.itemTitle}</strong>
          <span class="history-room-price">${formatPrice(room.currentPrice)}</span>
        </div>
        <p>${room.anchorName} · ${room.roomId}</p>
        <div class="history-room-meta">
          <span>${room.bidCount} 次出价</span>
          <span>${room.status === "CLOSED" ? "已结束" : "竞拍中"}</span>
        </div>
      </div>
    </button>
  `;
}

function renderPage() {
  bottomNavItems.forEach((item) => {
    item.classList.toggle("active", item.dataset.tab === state.activeTab);
  });

  renderFeedback();

  if (state.activeTab === "profile") {
    renderProfileView();
    bindPlaceholderButtons();
    return;
  }

  if (state.activeTab === "publish") {
    renderPublishView();
    bindPlaceholderButtons();
    return;
  }

  if (state.selectedRoomId) {
    renderRoomView();
    bindPlaceholderButtons();
    return;
  }

  renderLobbyView();
}

function bindPlaceholderButtons() {
  document.querySelectorAll("[data-placeholder]").forEach((element) => {
    if (element.dataset.bound === "true") {
      return;
    }

    element.dataset.bound = "true";
    element.addEventListener("click", () => {
      setFeedback(element.dataset.placeholder);
    });
  });
}

function bindBidAmountControls(room) {
  const amountInput = document.querySelector("#bidAmountInput");
  if (!amountInput) {
    return;
  }

  const minNextBid = Number(room.minNextBid);
  const currentPrice = Number(room.currentPrice);
  const stepPrice = Number(room.stepPrice || 1);

  const normalizeAmount = (value) => {
    const amount = Number(value);
    if (!Number.isFinite(amount)) {
      return currentPrice;
    }
    return Math.max(minNextBid, Number(amount.toFixed(2)));
  };

  const setAmount = (value) => {
    amountInput.value = normalizeAmount(value).toFixed(2);
  };

  document.querySelectorAll("[data-bid-adjust]").forEach((button) => {
    button.addEventListener("click", () => {
      const direction = Number(button.getAttribute("data-bid-adjust"));
      const baseAmount = Number(amountInput.value || currentPrice);
      const nextAmount = normalizeAmount(baseAmount + direction * stepPrice);
      setAmount(nextAmount);
    });
  });

  amountInput.addEventListener("blur", () => {
    setAmount(amountInput.value);
  });
}

function updateCountdownDisplay() {
  document.querySelectorAll("[data-room-countdown]").forEach((element) => {
    const roomId = element.getAttribute("data-room-countdown");
    const room = state.rooms.find((item) => item.roomId === roomId);
    if (!room) {
      return;
    }
    element.textContent = formatCountdown(room.secondsRemaining);
  });

  const roomCountdownValueEl = document.querySelector("#roomCountdownValue");
  if (roomCountdownValueEl && state.selectedRoom) {
    roomCountdownValueEl.textContent = formatCountdown(state.selectedRoom.secondsRemaining);
  }
}

async function loadRooms() {
  state.rooms = await fetchRooms();

  if (state.selectedRoomId) {
    const stillExists = state.rooms.some((room) => room.roomId === state.selectedRoomId);
    if (!stillExists) {
      state.selectedRoomId = null;
      state.selectedRoom = null;
    }
  }
}

async function loadUsers() {
  state.users = await fetchUsers();
  syncCurrentUser();
}

async function openRoom(roomId) {
  try {
    state.selectedRoomId = roomId;
    state.selectedRoom = await fetchRoom(roomId);
    state.socket?.subscribeRoom(roomId);
    renderPage();
  } catch (error) {
    setFeedback(error.message, true);
  }
}

async function handleCreateRoom(event) {
  event.preventDefault();
  const currentUser = getCurrentUser();
  const formData = new FormData(event.currentTarget);
  const payload = Object.fromEntries(formData.entries());
  payload.anchorName = payload.anchorName || currentUser?.nickname || "匿名主播";
  payload.startPrice = Number(payload.startPrice);
  payload.stepPrice = Number(payload.stepPrice);
  payload.durationSeconds = Number(payload.durationSeconds);

  try {
    const room = await createRoom(payload);
    event.currentTarget.reset();
    event.currentTarget.elements.anchorName.value = currentUser?.nickname || "";
    setFeedback(`已发布房间 ${room.roomId}`);
    state.activeTab = "home";
    await loadRooms();
    renderPage();
  } catch (error) {
    setFeedback(error.message, true);
  }
}

async function handleUpdateProfile(event) {
  event.preventDefault();
  const currentUser = getCurrentUser();
  if (!currentUser) {
    setFeedback("当前没有可编辑的用户", true);
    return;
  }

  const formData = new FormData(event.currentTarget);
  const payload = Object.fromEntries(formData.entries());
  payload.avatarUrl = payload.avatarUrl.trim() || currentUser.avatarUrl || "";
  if (!payload.password.trim()) {
    delete payload.password;
  }

  try {
    const updatedUser = await updateUser(currentUser.userId, payload);
    state.users = state.users.map((user) => (user.userId === updatedUser.userId ? updatedUser : user));
    syncCurrentUser();

    if (state.selectedRoom && state.selectedRoom.leaderNickname === currentUser.nickname) {
      state.selectedRoom.leaderNickname = updatedUser.nickname;
    }

    state.rooms = state.rooms.map((room) => ({
      ...room,
      leaderNickname: room.leaderNickname === currentUser.nickname ? updatedUser.nickname : room.leaderNickname,
      anchorName: room.anchorName === currentUser.nickname ? updatedUser.nickname : room.anchorName,
    }));

    setFeedback("个人资料已保存");
    renderPage();
  } catch (error) {
    setFeedback(error.message, true);
  }
}

async function handleAvatarSelected(event) {
  const currentUser = getCurrentUser();
  const file = event.target.files?.[0];

  if (!currentUser || !file) {
    return;
  }

  if (!file.type.startsWith("image/")) {
    setFeedback("请选择图片文件作为头像", true);
    event.target.value = "";
    return;
  }

  if (file.size > 5 * 1024 * 1024) {
    setFeedback("头像图片不能超过 5MB", true);
    event.target.value = "";
    return;
  }

  try {
    const uploadPolicy = await createAvatarUploadPolicy({
      userId: currentUser.userId,
      fileName: file.name,
      contentType: file.type,
    });
    const avatarUrl = await uploadAvatarToOss(file, uploadPolicy);
    const updatedUser = await updateUser(currentUser.userId, {
      nickname: currentUser.nickname,
      avatarUrl,
      bio: currentUser.bio || "",
    });

    state.users = state.users.map((user) => (user.userId === updatedUser.userId ? updatedUser : user));
    syncCurrentUser();
    setFeedback("头像已更新");
    renderPage();
  } catch (error) {
    setFeedback(error.message, true);
  } finally {
    event.target.value = "";
  }
}

async function handleBid(event) {
  event.preventDefault();
  if (!state.selectedRoomId) {
    setFeedback("请先进入一个拍卖房间", true);
    return;
  }

  const currentUser = getCurrentUser();
  if (!currentUser) {
    setFeedback("请先在“我的”里选择一个演示账号", true);
    return;
  }

  const formData = new FormData(event.currentTarget);
  const payload = Object.fromEntries(formData.entries());
  payload.amount = Number(payload.amount);
  payload.userId = currentUser.account;
  payload.nickname = currentUser.nickname;

  try {
    const room = await createBid(state.selectedRoomId, payload);
    state.selectedRoom = room;
    state.rooms = state.rooms.map((item) => (item.roomId === room.roomId ? room : item));
    setFeedback(`出价成功，当前领先者：${room.leaderNickname}`);
    renderPage();
  } catch (error) {
    setFeedback(error.message, true);
  }
}

async function bootstrap() {
  try {
    await Promise.all([loadRooms(), loadUsers()]);
    renderPage();
  } catch (error) {
    setFeedback(error.message, true);
  }
}

bottomNavItems.forEach((item) => {
  item.addEventListener("click", () => {
    if (!item.dataset.tab) {
      return;
    }
    state.activeTab = item.dataset.tab;
    if (item.dataset.tab !== "home") {
      state.selectedRoomId = null;
      state.selectedRoom = null;
    }
    renderPage();
  });
});

bindPlaceholderButtons();

state.socket = createAuctionSocket({
  onLobbyMessage(rooms) {
    state.rooms = rooms;
    renderPage();
  },
  onRoomMessage(room) {
    if (room.roomId === state.selectedRoomId) {
      state.selectedRoom = room;
      renderPage();
    }
  },
});

bootstrap();

setInterval(() => {
  if (state.selectedRoom) {
    state.selectedRoom.secondsRemaining = Math.max(0, state.selectedRoom.secondsRemaining - 1);
  }

  state.rooms = state.rooms.map((room) => ({
    ...room,
    secondsRemaining: Math.max(0, room.secondsRemaining - 1),
  }));

  updateCountdownDisplay();
}, 1000);

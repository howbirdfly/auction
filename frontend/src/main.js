import "./styles.css";
import {
  createBid,
  createRoom,
  fetchRoom,
  fetchRooms,
  fetchUsers,
  updateUser,
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
  { icon: "🎁", label: "盲盒潮玩" },
  { icon: "💎", label: "宝藏专场" },
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
  return `¥${Number(value).toFixed(2)}`;
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

function renderHomeHeader() {
  return `
    <section class="home-top">
      <div class="search-row">
        <button class="badge-button">签到</button>
        <div class="search-shell">
          <span class="search-placeholder">搜索拍品、房间或主播</span>
          <span class="search-actions">📷</span>
          <button class="search-button">搜索</button>
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
          <button class="category-item placeholder" data-placeholder="${category.label}功能后续接入">
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
      <span>去围观</span>
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
          <span>房间号 ${room.roomId}</span>
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

    <section class="feed-grid">
      ${
        state.rooms.length
          ? state.rooms.map(renderRoomCard).join("")
          : `<div class="empty-card">暂时还没有房间，去“我的”里先创建一个体验房间吧。</div>`
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
            <p>请先到“我的”里选择一个当前演示账号，再回来出价。</p>
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
        <input
          name="amount"
          type="number"
          min="0.01"
          step="0.01"
          placeholder="本次出价金额"
          value="${Number(room.minNextBid).toFixed(2)}"
          required
        />
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
          <span class="status-pill ${room.status === "CLOSED" ? "closed" : ""}">
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
}

function renderProfileView() {
  const currentUser = getCurrentUser();
  const liveRooms = getLiveRooms();
  const leadingRooms = getCurrentUserLeadingRooms();
  const createdRooms = state.rooms.filter((room) => room.anchorName === currentUser?.nickname);
  const displayRooms = createdRooms.length ? createdRooms : leadingRooms;

  if (!currentUser) {
    screenEl.innerHTML = `
      <section class="profile-screen">
        <section class="profile-card">
          <p class="eyebrow">My Center</p>
          <h1>个人中心</h1>
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
            <p class="profile-account">这里展示当前账号资料、我创建的房间和竞拍状态。</p>
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
          <img class="profile-avatar" src="${getAvatarUrl(currentUser)}" alt="${currentUser.nickname}" />
          <div>
            <p class="eyebrow">MY AUCTION PROFILE</p>
            <h1>${currentUser.nickname}</h1>
            <p class="profile-account">@${currentUser.account} · ${currentUser.userId}</p>
          </div>
        </div>
        <p class="profile-bio">${currentUser.bio || "这个用户还没有写简介。"}</p>

        <div class="profile-inline-actions">
          <button class="ghost-button" id="jumpToEditorInline">编辑主页资料</button>
          <button class="ghost-button" id="jumpToCreateRoom">创建房间</button>
        </div>
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
          <strong>${liveRooms.length}</strong>
        </div>
        <div class="metric-card">
          <span>加入时间</span>
          <strong>${formatShortTime(currentUser.createdAt)}</strong>
        </div>
      </section>

      <section class="room-panel">
        <div class="section-header compact">
          <div>
            <h2>我的房间</h2>
            <p>这里优先展示你当前创建的房间；如果还没创建，就先展示你正在领先的房间。</p>
          </div>
        </div>
        <div class="history-room-list">
          ${
            displayRooms.length
              ? displayRooms
                  .map(
                    (room) => `
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
                    `,
                  )
                  .join("")
              : `<div class="empty-card">你还没有关联展示中的房间记录，后面接入真实历史表后这里会更完整。</div>`
          }
        </div>
      </section>

      <section class="room-panel">
        <div id="profileEditorAnchor"></div>
        <div class="section-header compact">
          <div>
            <h2>编辑资料</h2>
            <p>这里已经连上后端用户接口。头像地址只在你需要更换时再填写，不会直接在主页摊开。</p>
          </div>
        </div>
        <form id="profileForm" class="stack-form">
          <input name="nickname" placeholder="昵称" value="${currentUser.nickname}" required />
          <input name="avatarUrl" placeholder="需要换头像时，再粘贴新的图片链接" />
          <textarea name="bio" rows="4" placeholder="个人简介">${currentUser.bio || ""}</textarea>
          <input name="password" type="password" placeholder="新密码，不修改可留空" />
          <button type="submit">保存资料</button>
        </form>
      </section>

      <section class="room-panel" id="createRoomAnchor">
        <div class="section-header compact">
          <div>
            <h2>创建房间</h2>
            <p>当前会默认带入你的昵称作为主播名，方便你直接发布新的竞拍房间。</p>
          </div>
        </div>
        <form id="createForm" class="stack-form">
          <input name="itemTitle" placeholder="拍品名称" required />
          <input name="anchorName" placeholder="主播名称" value="${currentUser.nickname}" required />
          <input name="imageUrl" placeholder="封面图链接（可选）" />
          <input name="startPrice" type="number" min="0.01" step="0.01" placeholder="起拍价" required />
          <input name="stepPrice" type="number" min="0.01" step="0.01" placeholder="加价幅度" required />
          <input name="durationSeconds" type="number" min="30" step="1" placeholder="时长（秒）" required />
          <button type="submit">创建房间</button>
        </form>
      </section>
    </section>
  `;

  screenEl.querySelector("#currentUserSelect")?.addEventListener("change", (event) => {
    setCurrentUser(event.target.value);
  });
  screenEl.querySelectorAll("#jumpToEditor, #jumpToEditorInline").forEach((button) => {
    button.addEventListener("click", () => {
      document.querySelector("#profileEditorAnchor")?.scrollIntoView({ behavior: "smooth", block: "start" });
    });
  });
  screenEl.querySelector("#jumpToCreateRoom")?.addEventListener("click", () => {
    document.querySelector("#createRoomAnchor")?.scrollIntoView({ behavior: "smooth", block: "start" });
  });
  screenEl.querySelectorAll(".history-room-item[data-room-id]").forEach((button) => {
    button.addEventListener("click", () => {
      state.activeTab = "home";
      openRoom(button.dataset.roomId);
    });
  });
  screenEl.querySelector("#profileForm")?.addEventListener("submit", handleUpdateProfile);
  screenEl.querySelector("#createForm")?.addEventListener("submit", handleCreateRoom);
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
    setFeedback(`已创建房间 ${room.roomId}`);
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

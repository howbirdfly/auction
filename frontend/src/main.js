import "./styles.css";
import { createBid, createRoom, fetchRoom, fetchRooms } from "./api";
import { createAuctionSocket } from "./socket";

const DEFAULT_IMAGE = "https://placehold.co/800x600/f6f7fb/1f2937?text=Auction+Room";
const CHANNELS = ["关注", "推荐", "新发", "省钱神券", "找服务", "热卖"];
const CATEGORIES = [
  { icon: "🍔", label: "吃喝玩乐" },
  { icon: "📱", label: "手机数码" },
  { icon: "♻️", label: "上门回收" },
  { icon: "🏠", label: "二手房" },
  { icon: "💳", label: "特惠充值" },
  { icon: "🎁", label: "盲盒潮玩" },
];

const state = {
  rooms: [],
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
      <button class="bottom-nav-item placeholder" data-placeholder="同城功能待接入">
        <span class="bottom-nav-icon">📍</span>
        <strong>同城</strong>
      </button>
      <button class="publish-button" data-placeholder="发布能力后续再接">
        <span>📷</span>
        <strong>发布</strong>
      </button>
      <button class="bottom-nav-item placeholder" data-placeholder="消息功能待接入">
        <span class="bottom-nav-icon">💬</span>
        <strong>消息</strong>
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

function getLiveRooms() {
  return state.rooms.filter((room) => room.status === "BIDDING");
}

function getClosedRooms() {
  return state.rooms.filter((room) => room.status === "CLOSED");
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
          <button class="category-item" data-placeholder="${category.label}功能后续接入">
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
          ${room.status === "CLOSED" ? "已结束" : "拍卖中"}
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
  const liveRooms = getLiveRooms();
  const closedRooms = getClosedRooms();

  screenEl.innerHTML = `
    ${renderHomeHeader()}
    ${renderCategoryRow()}
    ${renderPromoBanner()}

    <section class="home-summary">
      <div class="summary-chip">
        <span>竞拍中</span>
        <strong>${liveRooms.length}</strong>
      </div>
      <div class="summary-chip">
        <span>已结束</span>
        <strong>${closedRooms.length}</strong>
      </div>
      <button id="refreshRooms" class="refresh-chip">刷新房间</button>
    </section>

    <section class="feed-grid">
      ${
        state.rooms.length
          ? state.rooms.map(renderRoomCard).join("")
          : `<div class="empty-card">暂时还没有房间，去“我的”里先创建一个体验房间吧。</div>`
      }
    </section>
  `;

  screenEl.querySelector("#refreshRooms")?.addEventListener("click", loadRooms);
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
            ${room.status === "CLOSED" ? "已结束" : "拍卖中"}
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
                          <span>${new Date(bid.bidTime).toLocaleTimeString()}</span>
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

      <section class="room-panel bid-panel">
        <div class="section-header compact">
          <div>
            <h2>模拟出价</h2>
            <p>后续可以替换成真正的底部出价栏，这里先保留调试入口。</p>
          </div>
        </div>
        <form id="bidForm" class="stack-form">
          <input name="userId" placeholder="用户 ID" value="u10001" required />
          <input name="nickname" placeholder="用户昵称" value="竞拍用户A" required />
          <input name="amount" type="number" min="0.01" step="0.01" placeholder="出价金额" required />
          <button type="submit">立即出价</button>
        </form>
      </section>
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
  const liveRooms = getLiveRooms();
  const totalBids = state.rooms.reduce((sum, room) => sum + room.bidCount, 0);

  screenEl.innerHTML = `
    <section class="profile-screen">
      <section class="profile-card">
        <p class="eyebrow">My Center</p>
        <h1>个人中心</h1>
        <p>这里先承接建房和个人数据，后面可以继续扩成订单、保证金、历史参拍记录。</p>
      </section>

      <section class="profile-metrics">
        <div class="metric-card">
          <span>房间总数</span>
          <strong>${state.rooms.length}</strong>
        </div>
        <div class="metric-card">
          <span>竞拍中</span>
          <strong>${liveRooms.length}</strong>
        </div>
        <div class="metric-card">
          <span>总出价数</span>
          <strong>${totalBids}</strong>
        </div>
      </section>

      <section class="room-panel">
        <div class="section-header compact">
          <div>
            <h2>创建房间</h2>
            <p>商家先上传商品图链接，再配置起拍价和加价幅度。</p>
          </div>
        </div>
        <form id="createForm" class="stack-form">
          <input name="itemTitle" placeholder="拍品名称" required />
          <input name="anchorName" placeholder="主播名称" required />
          <input name="imageUrl" placeholder="封面图链接（可选）" />
          <input name="startPrice" type="number" min="0.01" step="0.01" placeholder="起拍价" required />
          <input name="stepPrice" type="number" min="0.01" step="0.01" placeholder="加价幅度" required />
          <input name="durationSeconds" type="number" min="30" step="1" placeholder="时长（秒）" required />
          <button type="submit">创建房间</button>
        </form>
      </section>
    </section>
  `;

  screenEl.querySelector("#createForm")?.addEventListener("submit", handleCreateRoom);
}

function renderPage() {
  bottomNavItems.forEach((item) => {
    item.classList.toggle("active", item.dataset.tab === state.activeTab);
  });

  renderFeedback();

  if (state.activeTab === "profile") {
    renderProfileView();
    return;
  }

  if (state.selectedRoomId) {
    renderRoomView();
    return;
  }

  renderLobbyView();
}

function bindPlaceholderButtons() {
  document.querySelectorAll("[data-placeholder]").forEach((element) => {
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
  try {
    state.rooms = await fetchRooms();

    if (state.selectedRoomId) {
      const stillExists = state.rooms.some((room) => room.roomId === state.selectedRoomId);
      if (!stillExists) {
        state.selectedRoomId = null;
        state.selectedRoom = null;
      }
    }

    renderPage();
  } catch (error) {
    setFeedback(error.message, true);
  }
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
  const formData = new FormData(event.currentTarget);
  const payload = Object.fromEntries(formData.entries());
  payload.startPrice = Number(payload.startPrice);
  payload.stepPrice = Number(payload.stepPrice);
  payload.durationSeconds = Number(payload.durationSeconds);

  try {
    const room = await createRoom(payload);
    event.currentTarget.reset();
    setFeedback(`已创建房间 ${room.roomId}`);
    state.activeTab = "home";
    await loadRooms();
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

  const formData = new FormData(event.currentTarget);
  const payload = Object.fromEntries(formData.entries());
  payload.amount = Number(payload.amount);

  try {
    const room = await createBid(state.selectedRoomId, payload);
    state.selectedRoom = room;
    setFeedback(`出价成功，当前领先者：${room.leaderNickname}`);
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

loadRooms();

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

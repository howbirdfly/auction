import "./styles.css";
import { createBid, createRoom, fetchRoom, fetchRooms } from "./api";
import { createAuctionSocket } from "./socket";

const state = {
  rooms: [],
  selectedRoomId: null,
  selectedRoom: null,
  socket: null,
  activeTab: "home",
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
        <span>首页</span>
        <strong>拍卖大厅</strong>
      </button>
      <button class="bottom-nav-item" data-tab="profile">
        <span>我的</span>
        <strong>个人中心</strong>
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

function getLiveRooms() {
  return state.rooms.filter((room) => room.status === "BIDDING");
}

function getClosedRooms() {
  return state.rooms.filter((room) => room.status === "CLOSED");
}

function renderLobbyView() {
  const liveRooms = getLiveRooms();
  const closedRooms = getClosedRooms();

  screenEl.innerHTML = `
    <section class="hero-card">
      <p class="eyebrow">Short Video Auction</p>
      <h1>直播竞拍房间</h1>
      <p class="hero-text">像刷直播间一样进入不同竞拍房，选择你感兴趣的场次直接参与出价。</p>
      <div class="hero-metrics">
        <div class="metric-card">
          <span>正在竞拍</span>
          <strong>${liveRooms.length}</strong>
        </div>
        <div class="metric-card">
          <span>已结束</span>
          <strong>${closedRooms.length}</strong>
        </div>
      </div>
    </section>

    <section class="section-block">
      <div class="section-header">
        <div>
          <h2>热门房间</h2>
          <p>点击任意房间即可进入实时竞拍页面。</p>
        </div>
        <button id="refreshRooms" class="ghost-button">刷新</button>
      </div>

      <div class="room-feed">
        ${
          state.rooms.length
            ? state.rooms
                .map(
                  (room) => `
                    <button class="feed-room-card" data-room-id="${room.roomId}">
                      <div class="feed-room-cover">
                        <span class="status-pill ${room.status === "CLOSED" ? "closed" : ""}">
                          ${room.status === "CLOSED" ? "已结束" : "竞拍中"}
                        </span>
                        <strong>${room.anchorName}</strong>
                      </div>
                      <div class="feed-room-body">
                        <h3>${room.itemTitle}</h3>
                        <div class="feed-room-meta">
                          <span>当前价 ${formatPrice(room.currentPrice)}</span>
                          <span>剩余 ${formatCountdown(room.secondsRemaining)}</span>
                        </div>
                        <div class="feed-room-footer">
                          <span>房间号 ${room.roomId}</span>
                          <span>${room.leaderNickname || "暂无领先者"}</span>
                        </div>
                      </div>
                    </button>
                  `,
                )
                .join("")
            : `<div class="empty-card">暂时没有房间，去“我的”里创建一个体验房间。</div>`
        }
      </div>
    </section>
  `;

  screenEl.querySelector("#refreshRooms")?.addEventListener("click", loadRooms);
  screenEl.querySelectorAll("[data-room-id]").forEach((button) => {
    button.addEventListener("click", () => {
      openRoom(button.dataset.roomId);
    });
  });
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
        <button id="backToLobby" class="back-button">返回大厅</button>
        <div class="room-topbar-meta">
          <span class="status-pill ${room.status === "CLOSED" ? "closed" : ""}">
            ${room.status === "CLOSED" ? "已结束" : "竞拍中"}
          </span>
          <strong>${room.roomId}</strong>
        </div>
      </header>

      <section class="room-hero">
        <p class="eyebrow">Auction Room</p>
        <h1>${room.itemTitle}</h1>
        <p>主播：${room.anchorName}</p>
      </section>

      <section class="room-summary">
        <div class="summary-card highlight">
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
        <div class="summary-card dark">
          <span>剩余时间</span>
          <strong>${formatCountdown(room.secondsRemaining)}</strong>
        </div>
      </section>

      <section class="room-panel">
        <div class="section-header compact">
          <div>
            <h2>实时出价</h2>
            <p>你可以在这里模拟用户参与竞拍。</p>
          </div>
        </div>
        <form id="bidForm" class="stack-form">
          <input name="userId" placeholder="用户 ID" value="u10001" required />
          <input name="nickname" placeholder="用户昵称" value="竞拍用户A" required />
          <input name="amount" type="number" min="0.01" step="0.01" placeholder="出价金额" required />
          <button type="submit">立即出价</button>
        </form>
      </section>

      <section class="room-panel">
        <div class="section-header compact">
          <div>
            <h2>最新出价记录</h2>
            <p>进入房间后所有用户都能看到这一列表的实时变化。</p>
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
              : `<div class="empty-card">还没有用户出价，当前房间等待第一位竞拍者。</div>`
          }
        </div>
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
        <p>这里可以先作为体验入口，后续你可以扩展成登录、我的订单、我的保证金、历史参拍记录。</p>
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
            <h2>创建体验房间</h2>
            <p>为了让首页有更多房间可选，这里保留一个快速建场入口。</p>
          </div>
        </div>
        <form id="createForm" class="stack-form">
          <input name="itemTitle" placeholder="拍品名称" required />
          <input name="anchorName" placeholder="主播名称" required />
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
    const active = item.dataset.tab === state.activeTab;
    item.classList.toggle("active", active);
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
    state.activeTab = item.dataset.tab;
    renderPage();
  });
});

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

  renderPage();
}, 1000);

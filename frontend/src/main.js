import "./styles.css";
import { createBid, createRoom, fetchRoom, fetchRooms } from "./api";
import { createAuctionSocket } from "./socket";

const state = {
  rooms: [],
  selectedRoomId: null,
  selectedRoom: null,
  socket: null,
};

const app = document.querySelector("#app");

app.innerHTML = `
  <div class="shell">
    <header class="hero">
      <div>
        <p class="eyebrow">Short Video Auction</p>
        <h1>直播竞拍交易闭环 Demo</h1>
        <p class="subtitle">包含创建拍卖房、实时出价、动态排名和倒计时延时的前后端骨架。</p>
      </div>
      <div class="hero-stats">
        <div class="stat-card">
          <span>实时房间</span>
          <strong id="roomCount">0</strong>
        </div>
        <div class="stat-card">
          <span>当前领先</span>
          <strong id="leaderName">--</strong>
        </div>
      </div>
    </header>

    <main class="grid">
      <section class="panel">
        <div class="panel-header">
          <h2>拍卖大厅</h2>
          <button id="refreshRooms" class="ghost-button">刷新</button>
        </div>
        <div id="roomList" class="room-list"></div>
      </section>

      <section class="panel spotlight">
        <div class="panel-header">
          <h2>竞拍房详情</h2>
          <span id="statusBadge" class="status-badge">未选择</span>
        </div>
        <div id="roomDetail" class="room-detail empty">请选择左侧拍卖房</div>
      </section>

      <section class="panel">
        <div class="panel-header">
          <h2>创建拍卖房</h2>
        </div>
        <form id="createForm" class="stack-form">
          <input name="itemTitle" placeholder="拍品名称" required />
          <input name="anchorName" placeholder="主播名称" required />
          <input name="startPrice" type="number" min="0.01" step="0.01" placeholder="起拍价" required />
          <input name="stepPrice" type="number" min="0.01" step="0.01" placeholder="加价幅度" required />
          <input name="durationSeconds" type="number" min="30" step="1" placeholder="时长(秒)" required />
          <button type="submit">创建房间</button>
        </form>
      </section>

      <section class="panel">
        <div class="panel-header">
          <h2>模拟出价</h2>
        </div>
        <form id="bidForm" class="stack-form">
          <input name="userId" placeholder="用户ID" value="u10001" required />
          <input name="nickname" placeholder="用户昵称" value="竞拍用户A" required />
          <input name="amount" type="number" min="0.01" step="0.01" placeholder="出价金额" required />
          <button type="submit">立即出价</button>
        </form>
        <p id="feedback" class="feedback"></p>
      </section>
    </main>
  </div>
`;

const roomCountEl = document.querySelector("#roomCount");
const leaderNameEl = document.querySelector("#leaderName");
const roomListEl = document.querySelector("#roomList");
const roomDetailEl = document.querySelector("#roomDetail");
const statusBadgeEl = document.querySelector("#statusBadge");
const feedbackEl = document.querySelector("#feedback");
const createFormEl = document.querySelector("#createForm");
const bidFormEl = document.querySelector("#bidForm");
const refreshButtonEl = document.querySelector("#refreshRooms");

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
  feedbackEl.textContent = message;
  feedbackEl.classList.toggle("error", isError);
}

function updateHero() {
  roomCountEl.textContent = String(state.rooms.length);
  leaderNameEl.textContent = state.selectedRoom?.leaderNickname || "--";
}

function renderRooms() {
  updateHero();

  if (!state.rooms.length) {
    roomListEl.innerHTML = `<div class="empty">暂无拍卖房，先在右侧创建一个。</div>`;
    return;
  }

  roomListEl.innerHTML = state.rooms
    .map(
      (room) => `
        <button class="room-card ${room.roomId === state.selectedRoomId ? "active" : ""}" data-room-id="${room.roomId}">
          <div class="room-card-top">
            <strong>${room.itemTitle}</strong>
            <span>${room.status}</span>
          </div>
          <p>${room.anchorName}</p>
          <div class="room-card-meta">
            <span>当前价 ${formatPrice(room.currentPrice)}</span>
            <span>剩余 ${formatCountdown(room.secondsRemaining)}</span>
          </div>
        </button>
      `,
    )
    .join("");

  roomListEl.querySelectorAll("[data-room-id]").forEach((button) => {
    button.addEventListener("click", () => {
      selectRoom(button.dataset.roomId);
    });
  });
}

function renderRoomDetail() {
  const room = state.selectedRoom;
  if (!room) {
    roomDetailEl.innerHTML = `<div class="empty">请选择左侧拍卖房</div>`;
    statusBadgeEl.textContent = "未选择";
    return;
  }

  statusBadgeEl.textContent = room.status;
  statusBadgeEl.classList.toggle("closed", room.status === "CLOSED");

  roomDetailEl.innerHTML = `
    <div class="detail-top">
      <div>
        <h3>${room.itemTitle}</h3>
        <p>主播：${room.anchorName}</p>
      </div>
      <div class="countdown">${formatCountdown(room.secondsRemaining)}</div>
    </div>

    <div class="price-board">
      <div>
        <span>当前价</span>
        <strong>${formatPrice(room.currentPrice)}</strong>
      </div>
      <div>
        <span>下一口起拍</span>
        <strong>${formatPrice(room.minNextBid)}</strong>
      </div>
      <div>
        <span>领先者</span>
        <strong>${room.leaderNickname || "暂无"}</strong>
      </div>
    </div>

    <div class="timeline">
      <h4>最新出价</h4>
      ${
        room.recentBids.length
          ? room.recentBids
              .map(
                (bid) => `
                  <div class="bid-row">
                    <span>${bid.nickname}</span>
                    <strong>${formatPrice(bid.amount)}</strong>
                    <time>${new Date(bid.bidTime).toLocaleTimeString()}</time>
                  </div>
                `,
              )
              .join("")
          : `<div class="empty-inline">还没有人出价</div>`
      }
    </div>
  `;
}

async function loadRooms() {
  try {
    state.rooms = await fetchRooms();
    if (!state.selectedRoomId && state.rooms[0]) {
      await selectRoom(state.rooms[0].roomId);
    } else {
      renderRooms();
      updateHero();
    }
  } catch (error) {
    setFeedback(error.message, true);
  }
}

async function selectRoom(roomId) {
  state.selectedRoomId = roomId;
  state.selectedRoom = await fetchRoom(roomId);
  state.socket?.subscribeRoom(roomId);
  renderRooms();
  renderRoomDetail();
  updateHero();
}

createFormEl.addEventListener("submit", async (event) => {
  event.preventDefault();
  const formData = new FormData(createFormEl);
  const payload = Object.fromEntries(formData.entries());
  payload.startPrice = Number(payload.startPrice);
  payload.stepPrice = Number(payload.stepPrice);
  payload.durationSeconds = Number(payload.durationSeconds);

  try {
    const room = await createRoom(payload);
    createFormEl.reset();
    setFeedback(`已创建拍卖房 ${room.roomId}`);
    await loadRooms();
    await selectRoom(room.roomId);
  } catch (error) {
    setFeedback(error.message, true);
  }
});

bidFormEl.addEventListener("submit", async (event) => {
  event.preventDefault();
  if (!state.selectedRoomId) {
    setFeedback("请先选择拍卖房", true);
    return;
  }

  const formData = new FormData(bidFormEl);
  const payload = Object.fromEntries(formData.entries());
  payload.amount = Number(payload.amount);

  try {
    const room = await createBid(state.selectedRoomId, payload);
    state.selectedRoom = room;
    renderRoomDetail();
    updateHero();
    setFeedback(`出价成功，当前领先者：${room.leaderNickname}`);
  } catch (error) {
    setFeedback(error.message, true);
  }
});

refreshButtonEl.addEventListener("click", loadRooms);

state.socket = createAuctionSocket({
  onLobbyMessage(rooms) {
    state.rooms = rooms;
    renderRooms();
    updateHero();
  },
  onRoomMessage(room) {
    if (room.roomId === state.selectedRoomId) {
      state.selectedRoom = room;
      renderRoomDetail();
      updateHero();
    }
  },
});

loadRooms();
setInterval(() => {
  if (state.selectedRoom) {
    state.selectedRoom.secondsRemaining = Math.max(0, state.selectedRoom.secondsRemaining - 1);
    renderRoomDetail();
  }

  state.rooms = state.rooms.map((room) => ({
    ...room,
    secondsRemaining: Math.max(0, room.secondsRemaining - 1),
  }));
  renderRooms();
}, 1000);

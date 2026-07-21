import "./styles.css";
import {
  createAvatarUploadPolicy,
  createBid,
  deleteRoom,
  createRoom,
  createRoomCoverUploadPolicy,
  fetchLeaderboard,
  fetchQualification,
  fetchRoom,
  fetchRooms,
  fetchUser,
  fetchUserAuctionHistory,
  fetchUsers,
  fetchWalletTransactions,
  rechargeUser,
  registerForAuction,
  updateUser,
  uploadAvatarToOss,
} from "./api";
import { createAuctionSocket } from "./socket";

const DEFAULT_IMAGE = "https://placehold.co/800x600/f6f7fb/1f2937?text=Auction+Room";
const DEFAULT_AVATAR = "https://placehold.co/256x256/f3f4f6/111827?text=User";
const CURRENT_USER_STORAGE_KEY = "auction-current-user-id";

const CHANNELS = [
  "\u5173\u6ce8",
  "\u63a8\u8350",
  "\u65b0\u53d1",
  "\u7701\u94b1\u795e\u5238",
  "\u627e\u670d\u52a1",
  "\u70ed\u5356",
];
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
  userAuctionHistory: null,
  userAuctionHistoryUserId: null,
  userAuctionHistoryLoading: false,
  walletTransactions: [],
  walletTransactionsUserId: null,
  walletTransactionsLoading: false,
  profileHistoryTab: "created",
  profileEditorOpen: false,
  profileRechargeOpen: false,
  profileWalletOpen: false,
  selectedRoomId: null,
  selectedRoom: null,
  selectedRoomLeaderboard: [],
  selectedRoomLeaderboardVersion: 0,
  selectedRoomQualification: null,
  socket: null,
  activeTab: "home",
  activeChannel: "推荐",
  feedback: "",
  feedbackError: false,
  draftRoomImageUrl: "",
  bidSubmitting: false,
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
        <span class="publish-entry-badge">📸</span>
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

function formatDateTime(value) {
  if (!value) {
    return "--";
  }
  return new Date(value).toLocaleString("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function getWalletTransactionTitle(transaction) {
  const titles = {
    RECHARGE: "账户充值",
    DEPOSIT_LOCK: "冻结报名保证金",
    DEPOSIT_RELEASE: "退回报名保证金",
    BID_FREEZE: "冻结领先出价",
    OUTBID_RELEASE: "被反超后释放金额",
    BID_SETTLEMENT: "成交扣款",
    DEPOSIT_SETTLEMENT_RELEASE: "结算退回保证金",
  };
  return titles[transaction.transactionType] || transaction.transactionType;
}

function formatSignedPrice(value) {
  const amount = Number(value || 0);
  if (amount > 0) {
    return `+${formatPrice(amount).slice(1)}`;
  }
  if (amount < 0) {
    return `-${formatPrice(Math.abs(amount)).slice(1)}`;
  }
  return formatPrice(0);
}

function setFeedback(message, isError = false) {
  state.feedback = message;
  state.feedbackError = isError;
  renderFeedback();
}

function createRequestId() {
  if (window.crypto?.randomUUID) {
    return window.crypto.randomUUID();
  }
  return `bid-${Date.now()}-${Math.random().toString(16).slice(2)}`;
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
    state.userAuctionHistory = null;
    state.userAuctionHistoryUserId = null;
    state.userAuctionHistoryLoading = false;
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
  invalidateUserAuctionHistory();
  invalidateWalletTransactions();
  renderPage();
  if (state.activeTab === "profile") {
    refreshUserAuctionHistory(true);
  }
}

async function refreshCurrentUser() {
  const currentUser = getCurrentUser();
  if (!currentUser) {
    return;
  }

  const updatedUser = await fetchUser(currentUser.userId);
  state.users = state.users.map((user) => (user.userId === updatedUser.userId ? updatedUser : user));
  syncCurrentUser();
}

function invalidateUserAuctionHistory() {
  state.userAuctionHistory = null;
  state.userAuctionHistoryUserId = null;
}

function invalidateWalletTransactions() {
  state.walletTransactions = [];
  state.walletTransactionsUserId = null;
  state.walletTransactionsLoading = false;
}

function getCurrentUserAuctionHistory() {
  const currentUser = getCurrentUser();
  if (!currentUser || state.userAuctionHistoryUserId !== currentUser.userId) {
    return null;
  }
  return state.userAuctionHistory;
}

function getCurrentWalletTransactions() {
  const currentUser = getCurrentUser();
  if (!currentUser || state.walletTransactionsUserId !== currentUser.userId) {
    return [];
  }
  return state.walletTransactions;
}

async function refreshUserAuctionHistory(force = false) {
  const currentUser = getCurrentUser();
  if (!currentUser) {
    invalidateUserAuctionHistory();
    state.userAuctionHistoryLoading = false;
    return;
  }

  if (!force && state.userAuctionHistoryUserId === currentUser.userId && state.userAuctionHistory) {
    return;
  }

  state.userAuctionHistoryLoading = true;
  if (state.activeTab === "profile") {
    renderPage();
  }

  try {
    const history = await fetchUserAuctionHistory(currentUser.userId);
    if (getCurrentUser()?.userId === currentUser.userId) {
      state.userAuctionHistory = history;
      state.userAuctionHistoryUserId = currentUser.userId;
    }
  } catch (error) {
    if (state.activeTab === "profile") {
      setFeedback(error.message, true);
    }
  } finally {
    state.userAuctionHistoryLoading = false;
    if (state.activeTab === "profile") {
      renderPage();
    }
  }
}

async function refreshWalletTransactions(force = false) {
  const currentUser = getCurrentUser();
  if (!currentUser) {
    invalidateWalletTransactions();
    return;
  }

  if (!force && state.walletTransactionsUserId === currentUser.userId && state.walletTransactions.length) {
    return;
  }

  state.walletTransactionsLoading = true;
  if (state.activeTab === "profile" && state.profileWalletOpen) {
    renderPage();
  }

  try {
    const transactions = await fetchWalletTransactions(currentUser.userId);
    if (getCurrentUser()?.userId === currentUser.userId) {
      state.walletTransactions = transactions;
      state.walletTransactionsUserId = currentUser.userId;
    }
  } catch (error) {
    if (state.activeTab === "profile" && state.profileWalletOpen) {
      setFeedback(error.message, true);
    }
  } finally {
    state.walletTransactionsLoading = false;
    if (state.activeTab === "profile" && state.profileWalletOpen) {
      renderPage();
    }
  }
}

function getLiveRooms() {
  return state.rooms.filter((room) => room.status === "BIDDING");
}

function isRoomBiddingClosed(room) {
  return !room || room.status === "CLOSED" || Number(room.secondsRemaining || 0) <= 0;
}

function roomRequiresQualification(room) {
  return Boolean(room?.registrationRequired) || Number(room?.depositAmount || 0) > 0;
}

function canCurrentUserBid(room) {
  if (!roomRequiresQualification(room)) {
    return true;
  }

  return Boolean(state.selectedRoomQualification?.canBid);
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

function getRoomSortScore(room) {
  const statusScore = room.status === "BIDDING" ? 1 : 0;
  const roomNumber = Number(String(room.roomId || "").replace(/[^\d]/g, "")) || 0;
  return statusScore * 1000000 + roomNumber;
}

function sortRooms(rooms) {
  return [...rooms].sort((left, right) => getRoomSortScore(right) - getRoomSortScore(left));
}

function getRoomVersion(room) {
  return Number(room?.version || 0);
}

function getLeaderboardVersion() {
  return Number(state.selectedRoomLeaderboardVersion || 0);
}

function shouldApplyRoomUpdate(currentRoom, incomingRoom) {
  if (!currentRoom) {
    return true;
  }

  return getRoomVersion(incomingRoom) >= getRoomVersion(currentRoom);
}

function mergeRoomsByVersion(currentRooms, incomingRooms) {
  return sortRooms(
    incomingRooms.map((incomingRoom) => {
      const currentRoom = currentRooms.find((room) => room.roomId === incomingRoom.roomId);
      return shouldApplyRoomUpdate(currentRoom, incomingRoom) ? incomingRoom : currentRoom;
    }),
  );
}

function upsertRoom(room) {
  const currentRoom = state.rooms.find((item) => item.roomId === room.roomId);
  const nextRoom = shouldApplyRoomUpdate(currentRoom, room) ? room : currentRoom;

  if (!currentRoom) {
    state.rooms = sortRooms([...state.rooms, nextRoom]);
    return;
  }

  state.rooms = sortRooms(state.rooms.map((item) => (item.roomId === nextRoom.roomId ? nextRoom : item)));
}

function applySelectedRoom(room) {
  if (!shouldApplyRoomUpdate(state.selectedRoom, room)) {
    return false;
  }

  state.selectedRoom = room;
  return true;
}

function applySelectedRoomLeaderboard(leaderboard, version = getRoomVersion(state.selectedRoom)) {
  const safeVersion = Number(version || 0);
  const minimumVersion = Math.max(getLeaderboardVersion(), getRoomVersion(state.selectedRoom));
  if (safeVersion < minimumVersion) {
    return false;
  }

  state.selectedRoomLeaderboard = Array.isArray(leaderboard) ? leaderboard : [];
  state.selectedRoomLeaderboardVersion = safeVersion;
  return true;
}

function clearSelectedRoom() {
  state.selectedRoomId = null;
  state.selectedRoom = null;
  state.selectedRoomLeaderboard = [];
  state.selectedRoomLeaderboardVersion = 0;
  state.selectedRoomQualification = null;
  state.socket?.subscribeRoom(null);
}

function syncSelectedRoomRealtime() {
  if (!state.selectedRoomId || !state.selectedRoom?.hot) {
    state.socket?.subscribeRoom(null);
    return;
  }

  state.socket?.subscribeRoom(state.selectedRoomId);
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
          ${room.status === "CLOSED" ? "\u5df2\u7ed3\u675f" : "\u7ade\u62cd\u4e2d"}
        </span>
      </div>
      <div class="feed-room-body">
        <h3>${room.itemTitle}</h3>
        <div class="feed-room-price">
          <strong>${formatPrice(room.currentPrice)}</strong>
          <span data-room-countdown="${room.roomId}">${formatCountdown(room.secondsRemaining)}</span>
        </div>
        <p class="feed-room-note">${room.anchorName} · ${room.leaderNickname || "\u6682\u65e0\u9886\u5148\u8005"}</p>
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
      setFeedback("\u623f\u95f4\u5217\u8868\u5df2\u5237\u65b0");
      renderPage();
    } catch (error) {
      setFeedback(error.message, true);
    }
  });

  bindPlaceholderButtons();
}

function renderQualificationPanel(room) {
  if (!roomRequiresQualification(room)) {
    return "";
  }

  const currentUser = getCurrentUser();
  const qualification = state.selectedRoomQualification;
  const bidClosed = isRoomBiddingClosed(room);
  const isQualified = Boolean(qualification?.canBid);
  const statusText = isQualified
    ? "\u5df2\u62a5\u540d\uff0c\u53ef\u76f4\u63a5\u51fa\u4ef7"
    : bidClosed
      ? "\u623f\u95f4\u5df2\u7ed3\u675f"
      : qualification
        ? "\u672a\u62a5\u540d\uff0c\u6682\u4e0d\u80fd\u51fa\u4ef7"
        : "姝ｅ湪鏍￠獙璧勬牸";
  const description = isQualified
    ? `\u5f53\u524d\u8d26\u53f7\u5df2\u51bb\u7ed3\u4fdd\u8bc1\u91d1 ${formatPrice(qualification.depositAmount)}\uff0c\u53ef\u4ee5\u76f4\u63a5\u53c2\u4e0e\u672c\u573a\u7ade\u62cd\u3002`
    : bidClosed
      ? "\u5f53\u524d\u623f\u95f4\u5df2\u7ecf\u7ed3\u675f\uff0c\u4e0d\u80fd\u518d\u62a5\u540d\u7ade\u62cd\u3002"
      : qualification
        ? qualification.message
        : "\u6b63\u5728\u540c\u6b65\u5f53\u524d\u8d26\u53f7\u7684\u7ade\u62cd\u8d44\u683c\uff0c\u8bf7\u7a0d\u5019\u3002";

  if (!currentUser) {
    return `
      <section class="room-panel qualification-panel">
        <div class="section-header compact">
          <div>
            <h2>竞拍资格</h2>
            <p>本房间开启了报名竞拍，参与出价前需要先锁定保证金。</p>
          </div>
        </div>
        <div class="qualification-card">
          <div class="qualification-meta">
            <strong>保证金 ${formatPrice(room.depositAmount)}</strong>
            <span>请先到“我的”里选择一个账号，再回来报名竞拍。</span>
          </div>
        </div>
      </section>
    `;
  }

  return `
    <section class="room-panel qualification-panel">
      <div class="section-header compact">
        <div>
          <h2>竞拍资格</h2>
          <p>报名通过后会为当前账号冻结保证金，未报名的账号不能直接出价。</p>
        </div>
      </div>
      <div class="qualification-card ${isQualified ? "qualified" : ""}">
        <div class="qualification-meta">
          <strong>${statusText}</strong>
          <span>${description}</span>
        </div>
        <div class="qualification-side">
          <b>${formatPrice(qualification?.depositAmount ?? room.depositAmount)}</b>
          <small>保证金</small>
        </div>
      </div>
      ${
        !bidClosed && qualification && !qualification.canBid
          ? `<button type="button" class="qualification-button" id="registerAuctionButton">报名竞拍并冻结保证金</button>`
          : ""
      }
    </section>
  `;
}

function renderBidPanel(room) {
  const currentUser = getCurrentUser();
  const bidClosed = isRoomBiddingClosed(room);
  const qualificationPending = roomRequiresQualification(room) && Boolean(currentUser) && !state.selectedRoomQualification;
  const qualificationBlocked = roomRequiresQualification(room) && Boolean(currentUser) && !qualificationPending && !canCurrentUserBid(room);
  const bidDisabled = bidClosed || qualificationPending || qualificationBlocked || state.bidSubmitting;
  const disabledAttr = bidDisabled ? "disabled" : "";

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

  if (bidClosed) {
    return `
      <section class="room-panel bid-panel bid-finished-panel">
        <div class="section-header compact">
          <div>
            <h2>竞拍已结束</h2>
            <p>本场已经进入结算状态，当前页面展示的是最终成交结果和落槌记录。</p>
          </div>
        </div>
        <div class="bid-closed-note">你现在不能继续出价，可以返回首页查看其他正在进行中的房间。</div>
        <button type="button" class="secondary-button" id="settlementBackHomeButton">返回首页继续逛房间</button>
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

      ${bidClosed ? `<div class="bid-closed-note">本场竞拍已结束，可以返回首页看看其他房间。</div>` : ""}

      ${qualificationPending ? `<div class="bid-closed-note">正在校验当前账号的竞拍资格，请稍候。</div>` : ""}
      ${qualificationBlocked ? `<div class="bid-closed-note">请先报名竞拍并冻结保证金，再参与当前房间出价。</div>` : ""}
      <form id="bidForm" class="stack-form">
        <div class="bid-amount-panel">
          <div class="bid-amount-label">
            <span>本次出价</span>
            <strong>每次加价 ${formatPrice(room.stepPrice)}</strong>
          </div>
          <div class="bid-stepper">
            <button type="button" class="bid-step-button" data-bid-adjust="-1" ${disabledAttr}>-</button>
            <input
              id="bidAmountInput"
              name="amount"
              type="number"
              min="0.01"
              step="0.01"
              inputmode="decimal"
              placeholder="本次出价金额"
              value="${Number(room.currentPrice).toFixed(2)}"
              ${disabledAttr}
              required
            />
            <button type="button" class="bid-step-button" data-bid-adjust="1" ${disabledAttr}>+</button>
          </div>
        </div>
        <button type="submit" ${disabledAttr}>${bidClosed ? "\u7ade\u62cd\u5df2\u7ed3\u675f" : state.bidSubmitting ? "正在出价..." : "\u7acb\u5373\u51fa\u4ef7"}</button>
      </form>
    </section>
  `;
}

function renderLeaderboardPanel() {
  const leaderboard = state.selectedRoomLeaderboard || [];
  const currentUser = getCurrentUser();
  const roomClosed = isRoomBiddingClosed(state.selectedRoom);

  return `
    <section class="room-panel">
      <div class="section-header compact">
        <div>
          <h2>${roomClosed ? "最终排行" : "实时排行"}</h2>
          <p>${roomClosed ? "这里保留本场竞拍结束时的最终排名结果。" : "临近结束时，这里会实时刷新当前领先的出价用户。"}</p>
        </div>
      </div>
      <div class="leaderboard-list">
        ${
          leaderboard.length
            ? leaderboard
                .map(
                  (entry) => {
                    const matchedUser =
                      state.users.find((user) => user.account === entry.userId) ||
                      state.users.find((user) => user.userId === entry.userId) ||
                      state.users.find((user) => user.nickname === entry.nickname);

                    return `
                    <div class="leaderboard-row ${currentUser?.account === entry.userId ? "current-user" : ""}">
                      <div class="leaderboard-user">
                        <span class="leaderboard-rank">#${entry.rank}</span>
                        <img
                          class="leaderboard-avatar"
                          src="${getAvatarUrl(matchedUser)}"
                          alt="${entry.nickname}"
                          onerror="this.src='${DEFAULT_AVATAR}'"
                        />
                        <div>
                          <strong>${entry.nickname}</strong>
                          <span>${entry.userId}</span>
                        </div>
                      </div>
                      <b class="leaderboard-amount">${formatPrice(entry.amount)}</b>
                    </div>
                  `;
                  },
                )
                .join("")
            : `<div class="empty-card">当前还没有上榜出价。</div>`
        }
      </div>
    </section>
  `;
}

function renderSettlementPanel(room) {
  if (room.status !== "CLOSED") {
    return "";
  }

  const latestBid = room.recentBids?.[0] || null;
  const winner =
    state.users.find((user) => user.nickname === room.leaderNickname) ||
    state.users.find((user) => user.account === room.leaderNickname) ||
    null;
  const hasWinner = Boolean(room.leaderNickname && room.bidCount > 0);
  const winnerIdentity = winner
    ? `@${winner.account} · ${winner.userId}`
    : latestBid?.userId || "无人出价";
  const settlementHint = room.registrationRequired
    ? "报名保证金和竞拍冻结金额会在结算后统一处理，可以去“我的”里查看余额变化。"
    : "本场没有报名门槛，房间已完成最终成交结算。";

  return `
    <section class="room-panel settlement-panel ${hasWinner ? "sold" : "unsold"}">
      <div class="section-header compact">
        <div>
          <h2>${hasWinner ? "\u6210\u4ea4\u7ed3\u7b97" : "\u672c\u573a\u7ed3\u679c"}</h2>
          <p>${hasWinner ? "\u7ade\u62cd\u5df2\u7ecf\u7ed3\u675f\uff0c\u4ee5\u4e0b\u662f\u672c\u573a\u623f\u95f4\u7684\u6700\u7ec8\u6210\u4ea4\u7ed3\u679c\u3002" : "\u672c\u573a\u7ade\u62cd\u5df2\u7ed3\u675f\uff0c\u4f46\u672a\u4ea7\u751f\u6709\u6548\u6210\u4ea4\u3002"}</p>
        </div>
        <span class="settlement-badge">${hasWinner ? "\u6210\u4ea4" : "\u6d41\u62cd"}</span>
      </div>

      ${
        hasWinner
          ? `
            <div class="settlement-winner">
              <img
                class="settlement-avatar"
                src="${getAvatarUrl(winner)}"
                alt="${room.leaderNickname}"
                onerror="this.src='${DEFAULT_AVATAR}'"
              />
              <div class="settlement-winner-meta">
                <strong>${room.leaderNickname}</strong>
                <span>${winnerIdentity}</span>
                <small>恭喜拍得本场商品</small>
              </div>
            </div>
          `
          : `
            <div class="settlement-empty">
              <strong>本场无人成交</strong>
              <span>可以调整起拍价、时长或商品信息后重新开拍。</span>
            </div>
          `
      }

      <div class="settlement-grid">
        <article class="settlement-metric highlight">
          <span>${hasWinner ? "\u6210\u4ea4\u4ef7" : "\u7ed3\u679c"}</span>
          <strong>${hasWinner ? formatPrice(room.currentPrice) : "\u672a\u6210\u4ea4"}</strong>
        </article>
        <article class="settlement-metric">
          <span>获胜者</span>
          <strong>${hasWinner ? room.leaderNickname : "无人出价"}</strong>
        </article>
        <article class="settlement-metric">
          <span>结束时间</span>
          <strong>${formatDateTime(room.endsAt)}</strong>
        </article>
        <article class="settlement-metric">
          <span>总出价次数</span>
          <strong>${room.bidCount}</strong>
        </article>
      </div>

      <div class="settlement-note-grid">
        <article class="settlement-note-card">
          <span>最后出价时间</span>
          <strong>${latestBid ? formatDateTime(latestBid.bidTime) : formatDateTime(room.endsAt)}</strong>
        </article>
        <article class="settlement-note-card">
          <span>结算说明</span>
          <strong>${settlementHint}</strong>
        </article>
      </div>
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
          <p class="eyebrow">拍卖房间</p>
          <h1>${room.itemTitle}</h1>
          <p>${room.anchorName}</p>
        </div>
      </section>

      <section class="room-summary">
        ${
          room.status === "CLOSED"
            ? `
              <div class="summary-card primary">
                <span>${room.bidCount > 0 ? "成交价" : "结算状态"}</span>
                <strong>${room.bidCount > 0 ? formatPrice(room.currentPrice) : "流拍"}</strong>
              </div>
              <div class="summary-card">
                <span>起拍价</span>
                <strong>${formatPrice(room.startPrice)}</strong>
              </div>
              <div class="summary-card">
                <span>获胜者</span>
                <strong>${room.leaderNickname || "无人出价"}</strong>
              </div>
              <div class="summary-card">
                <span>总出价次数</span>
                <strong>${room.bidCount}</strong>
              </div>
            `
            : `
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
            `
        }
      </section>

      ${renderSettlementPanel(room)}

      ${renderLeaderboardPanel()}

      ${renderQualificationPanel(room)}

      <section class="room-panel">
        <div class="section-header compact">
          <div>
            <h2>${room.status === "CLOSED" ? "落槌记录" : "最新出价记录"}</h2>
            <p>${room.status === "CLOSED" ? "这里保留了房间结束前的最终出价顺序。" : "房间内所有人都能看到这里的实时变化。"}</p>
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

      ${
        room.status === "CLOSED" && getCurrentUser()?.nickname === room.anchorName
          ? `
            <section class="room-panel room-danger-panel">
              <div class="section-header compact">
                <div>
                  <h2>删除房间</h2>
                  <p>竞拍结束后，你可以把这个房间从首页列表里移除。</p>
                </div>
              </div>
              <button type="button" class="danger-button" id="deleteExpiredRoomButton">删除已结束房间</button>
            </section>
          `
          : ""
      }

      ${renderBidPanel(room)}
    </section>
  `;

  screenEl.querySelector("#backToLobby")?.addEventListener("click", () => {
    clearSelectedRoom();
    renderPage();
  });

  screenEl.querySelector("#bidForm")?.addEventListener("submit", handleBid);
  screenEl.querySelector("#registerAuctionButton")?.addEventListener("click", handleRegisterForAuction);
  screenEl.querySelector("#settlementBackHomeButton")?.addEventListener("click", () => {
    clearSelectedRoom();
    state.activeTab = "home";
    renderPage();
  });
  screenEl.querySelector("#deleteExpiredRoomButton")?.addEventListener("click", () => {
    handleDeleteRoom(room.roomId);
  });
  bindBidAmountControls(room);
  syncBidPanelState();
}

function renderPublishView() {
  const currentUser = getCurrentUser();

  screenEl.innerHTML = `
    <section class="publish-screen">
      <section class="publish-hero">
        <p class="eyebrow">发布入口</p>
        <h1>发布竞拍房间</h1>
        <p class="hero-text">把创建房间单独放在这里，首页负责逛房间，个人页只保留和账号相关的内容。</p>
      </section>

      <section class="room-panel publish-panel">
        <div class="section-header compact">
          <div>
            <h2>创建拍卖房</h2>
            <p>${currentUser ? `\u5f53\u524d\u9ed8\u8ba4\u4f7f\u7528 ${currentUser.nickname} \u4f5c\u4e3a\u4e3b\u64ad\u540d\u3002` : "\u8bf7\u5148\u53bb\u201c\u6211\u7684\u201d\u91cc\u9009\u62e9\u4e00\u4e2a\u8d26\u53f7\u540e\u518d\u53d1\u5e03\u3002"}</p>
          </div>
        </div>
        <form id="createForm" class="stack-form publish-form">
          <div class="cover-upload-card">
            <div class="cover-upload-preview">
              <img
                id="roomCoverPreview"
                src="${state.draftRoomImageUrl || DEFAULT_IMAGE}"
                alt="房间封面预览"
                onerror="this.src='${DEFAULT_IMAGE}'"
              />
            </div>
            <div class="cover-upload-meta">
              <strong>房间封面</strong>
              <span>建议上传 1:1 或 4:3 的商品图</span>
            </div>
            <button type="button" class="ghost-button compact-button" id="uploadRoomCoverButton">上传图片</button>
            <input id="roomCoverFileInput" type="file" accept="image/png,image/jpeg,image/webp,image/gif" hidden />
          </div>

          <input name="itemTitle" placeholder="拍品名称" required />
          <input name="anchorName" placeholder="主播名称" value="${currentUser?.nickname || ""}" required />
          <input name="imageUrl" value="${state.draftRoomImageUrl}" hidden />
          <div class="compact-grid">
            <input name="startPrice" type="number" min="0.01" step="0.01" placeholder="\u8d77\u62cd\u4ef7" required />
            <input name="stepPrice" type="number" min="0.01" step="0.01" placeholder="加价幅度" required />
          </div>
          <label class="publish-toggle">
            <div class="publish-toggle-copy">
              <strong>开启报名竞拍</strong>
              <span>开启后，用户需要先冻结保证金才能参与出价。</span>
            </div>
            <input name="registrationRequired" type="checkbox" checked />
          </label>
          <input
            name="depositAmount"
            type="number"
            min="0"
            step="0.01"
            value="99.00"
            placeholder="保证金金额"
          />
          <input name="durationSeconds" type="number" min="30" step="1" placeholder="\u6301\u7eed\u65f6\u957f\uff08\u79d2\uff09" required />
          <button type="submit">立即发布房间</button>
        </form>
      </section>
    </section>
  `;

  const createForm = screenEl.querySelector("#createForm");
  createForm?.addEventListener("submit", handleCreateRoom);
  screenEl.querySelector("#uploadRoomCoverButton")?.addEventListener("click", () => {
    screenEl.querySelector("#roomCoverFileInput")?.click();
  });
  screenEl.querySelector("#roomCoverFileInput")?.addEventListener("change", handleRoomCoverSelected);
  createForm?.elements.registrationRequired?.addEventListener("change", () => {
    syncPublishDepositFieldState(createForm);
  });
  syncPublishDepositFieldState(createForm);
}

function syncPublishDepositFieldState(form) {
  if (!form) {
    return;
  }

  const registrationCheckbox = form.elements.registrationRequired;
  const depositInput = form.elements.depositAmount;
  if (!registrationCheckbox || !depositInput) {
    return;
  }

  const enabled = Boolean(registrationCheckbox.checked);
  depositInput.disabled = !enabled;
  depositInput.required = enabled;
  if (!enabled) {
    depositInput.value = "0.00";
    return;
  }

  if (!depositInput.value || Number(depositInput.value) <= 0) {
    depositInput.value = "99.00";
  }
}

function renderProfileView() {
  const currentUser = getCurrentUser();
  const createdRooms = getCurrentUserCreatedRooms();
  const createdClosedRooms = sortRooms(createdRooms.filter((room) => room.status === "CLOSED"));
  const auctionHistory = getCurrentUserAuctionHistory();
  const walletTransactions = getCurrentWalletTransactions();
  const registeredRooms = auctionHistory?.registeredRooms || [];
  const wonRooms = auctionHistory?.wonRooms || [];
  const missedRooms = auctionHistory?.missedRooms || [];

  if (!currentUser) {
    screenEl.innerHTML = `
      <section class="profile-screen">
        <section class="profile-card">
          <p class="eyebrow">个人中心</p>
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
            <p class="profile-account">这里会展示账号资料、我创建的房间和我当前领先的竞拍。</p>
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
          <div class="profile-action-buttons">
            <button class="ghost-button" id="openProfileRechargeButton">充值</button>
            <button class="ghost-button" id="openProfileWalletButton">资金明细</button>
            <button class="ghost-button" id="openProfileEditorButton">编辑个人资料</button>
          </div>
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
            <p class="eyebrow">我的竞拍资料</p>
            <h1>${currentUser.nickname}</h1>
            <p class="profile-account">@${currentUser.account} · ${currentUser.userId}</p>
          </div>
        </div>
        <p class="profile-bio">${currentUser.bio || "\u8fd9\u4e2a\u7528\u6237\u8fd8\u6ca1\u6709\u5199\u7b80\u4ecb\u3002"}</p>
        <div class="profile-inline-actions">
          <button type="button" class="ghost-button" id="profileCardRechargeButton">充值</button>
          <button type="button" class="ghost-button" id="profileCardEditButton">编辑个人资料</button>
        </div>
      </section>

      <section class="room-panel">
        <div class="section-header compact">
          <div>
            <h2>账户余额</h2>
            <p>这里可以给当前账号充值，保证金和领先中的出价金额都会从这里冻结或扣除。</p>
          </div>
        </div>
        <div class="profile-metrics">
          <div class="metric-card">
            <span>可用余额</span>
            <strong>${formatPrice(currentUser.balance)}</strong>
          </div>
          <div class="metric-card">
            <span>冻结金额</span>
            <strong>${formatPrice(currentUser.frozenAmount)}</strong>
          </div>
          <div class="metric-card">
            <span>总资产</span>
            <strong>${formatPrice(Number(currentUser.balance || 0) + Number(currentUser.frozenAmount || 0))}</strong>
          </div>
        </div>
      </section>

      <section class="profile-metrics">
        <div class="metric-card">
          <span>我创建的房间</span>
          <strong>${createdRooms.length}</strong>
        </div>
        <div class="metric-card">
          <span>报名记录</span>
          <strong>${state.userAuctionHistoryLoading ? "--" : registeredRooms.length}</strong>
        </div>
        <div class="metric-card">
          <span>拍到的房间</span>
          <strong>${state.userAuctionHistoryLoading ? "--" : wonRooms.length}</strong>
        </div>
        <div class="metric-card">
          <span>未拍到</span>
          <strong>${state.userAuctionHistoryLoading ? "--" : missedRooms.length}</strong>
        </div>
      </section>

      <section class="room-panel profile-history-shell">
        <div class="section-header compact">
          <div>
            <h2>竞拍历史</h2>
            <p>可以切换查看你创建过的房间，或者你亲自参与过的竞拍记录。</p>
          </div>
        </div>
        <div class="profile-history-tabs">
          <button
            type="button"
            class="profile-history-tab ${state.profileHistoryTab === "created" ? "active" : ""}"
            data-profile-history-tab="created"
          >
            我创建的
          </button>
          <button
            type="button"
            class="profile-history-tab ${state.profileHistoryTab === "participated" ? "active" : ""}"
            data-profile-history-tab="participated"
          >
            我参加的
          </button>
        </div>
        ${
          state.profileHistoryTab === "created"
            ? renderProfileHistorySection({
                title: "我创建的已结束房间",
                description: "这里会展示你发布且已经结束的房间，方便回看成交或流拍结果。",
                rooms: createdClosedRooms,
                loading: false,
                emptyMessage: "你还没有已结束的房间，先去发布一个试试吧。",
              })
            : `
              <div class="profile-history-group">
                ${renderProfileHistorySection({
                  title: "我报名过的房间",
                  description: "这里会展示你冻结过保证金、正式报名参与过的竞拍房间。",
                  rooms: registeredRooms,
                  loading: state.userAuctionHistoryLoading,
                  emptyMessage: "你还没有报名过任何竞拍房间。",
                })}
                ${renderProfileHistorySection({
                  title: "我拍到的房间",
                  description: "这些是你在竞拍结束后成功拍到商品的房间。",
                  rooms: wonRooms,
                  loading: state.userAuctionHistoryLoading,
                  emptyMessage: "你暂时还没有拍到过房间里的商品。",
                })}
                ${renderProfileHistorySection({
                  title: "我参与但未拍到",
                  description: "这些房间你参与过，但最终没有成为最后的成交获胜者。",
                  rooms: missedRooms,
                  loading: state.userAuctionHistoryLoading,
                  emptyMessage: "你当前还没有“参与但未拍到”的房间记录。",
                })}
              </div>
            `
        }
      </section>

      ${
        state.profileEditorOpen
          ? `
            <div class="profile-modal-backdrop" id="profileEditorBackdrop">
              <section class="profile-modal" role="dialog" aria-modal="true" aria-labelledby="profileEditorTitle">
                <div class="profile-modal-head">
                  <div>
                    <h2 id="profileEditorTitle">编辑个人资料</h2>
                    <p>头像建议直接用主页上的按钮上传，这里主要修改昵称、简介和密码。</p>
                  </div>
                  <button type="button" class="ghost-button profile-modal-close" id="closeProfileEditorButton">关闭</button>
                </div>
                <form id="profileForm" class="stack-form profile-modal-form">
                  <input name="nickname" placeholder="昵称" value="${currentUser.nickname}" required />
                  <input name="avatarUrl" placeholder="需要手动替换时再填写新的头像地址" />
                  <textarea name="bio" rows="4" placeholder="个人简介">${currentUser.bio || ""}</textarea>
                  <input name="password" type="password" placeholder="新密码，不修改可留空" />
                  <button type="submit">保存资料</button>
                </form>
              </section>
            </div>
          `
          : ""
      }
      ${
        state.profileRechargeOpen
          ? `
            <div class="profile-modal-backdrop" id="profileRechargeBackdrop">
              <section class="profile-modal" role="dialog" aria-modal="true" aria-labelledby="profileRechargeTitle">
                <div class="profile-modal-head">
                  <div>
                    <h2 id="profileRechargeTitle">账户充值</h2>
                    <p>给当前账号补充余额，保证金冻结和竞拍扣款都会从这里走。</p>
                  </div>
                  <button type="button" class="ghost-button profile-modal-close" id="closeProfileRechargeButton">关闭</button>
                </div>
                <form id="rechargeForm" class="stack-form profile-modal-form">
                  <input name="amount" type="number" min="0.01" step="0.01" placeholder="充值金额" required />
                  <button type="submit">立即充值</button>
                </form>
              </section>
            </div>
          `
          : ""
      }
      ${
        state.profileWalletOpen
          ? `
            <div class="profile-modal-backdrop" id="profileWalletBackdrop">
              <section class="profile-modal profile-wallet-modal" role="dialog" aria-modal="true" aria-labelledby="profileWalletTitle">
                <div class="profile-modal-head">
                  <div>
                    <h2 id="profileWalletTitle">资金明细</h2>
                    <p>这里集中展示充值、保证金冻结与释放、领先出价冻结、被反超释放和成交扣款。</p>
                  </div>
                  <button type="button" class="ghost-button profile-modal-close" id="closeProfileWalletButton">关闭</button>
                </div>
                <div class="wallet-summary-grid">
                  <div class="metric-card">
                    <span>可用余额</span>
                    <strong>${formatPrice(currentUser.balance)}</strong>
                  </div>
                  <div class="metric-card">
                    <span>冻结金额</span>
                    <strong>${formatPrice(currentUser.frozenAmount)}</strong>
                  </div>
                </div>
                <div class="wallet-transaction-list">
                  ${
                    state.walletTransactionsLoading
                      ? `<div class="empty-card">正在加载资金明细...</div>`
                      : walletTransactions.length
                        ? walletTransactions
                            .map(
                              (transaction) => `
                                <article class="wallet-transaction-item">
                                  <div class="wallet-transaction-head">
                                    <div>
                                      <strong>${getWalletTransactionTitle(transaction)}</strong>
                                      <p>${transaction.description || "资金变动记录"}</p>
                                    </div>
                                    <div class="wallet-transaction-deltas">
                                      <span class="wallet-delta ${Number(transaction.availableDelta || 0) >= 0 ? "positive" : "negative"}">
                                        可用 ${formatSignedPrice(transaction.availableDelta)}
                                      </span>
                                      <span class="wallet-delta ${Number(transaction.frozenDelta || 0) >= 0 ? "positive" : "negative"}">
                                        冻结 ${formatSignedPrice(transaction.frozenDelta)}
                                      </span>
                                    </div>
                                  </div>
                                  <div class="wallet-transaction-meta">
                                    <span>${formatDateTime(transaction.createdAt)}</span>
                                    <span>${transaction.referenceId || "系统记录"}</span>
                                  </div>
                                  <div class="wallet-transaction-foot">
                                    <span>可用余额 ${formatPrice(transaction.balanceAfter)}</span>
                                    <span>冻结余额 ${formatPrice(transaction.frozenAfter)}</span>
                                  </div>
                                </article>
                              `,
                            )
                            .join("")
                        : `<div class="empty-card">当前账号还没有资金流水，先充值或参与一场竞拍看看。</div>`
                  }
                </div>
              </section>
            </div>
          `
          : ""
      }
    </section>
  `;

  screenEl.querySelector("#currentUserSelect")?.addEventListener("change", (event) => {
    setCurrentUser(event.target.value);
  });
  screenEl.querySelector("#changeAvatarButton")?.addEventListener("click", () => {
    screenEl.querySelector("#avatarFileInput")?.click();
  });
  screenEl.querySelector("#avatarFileInput")?.addEventListener("change", handleAvatarSelected);
  const openProfileEditor = () => {
    state.profileEditorOpen = true;
    state.profileRechargeOpen = false;
    state.profileWalletOpen = false;
    renderPage();
  };
  const openProfileRecharge = () => {
    state.profileRechargeOpen = true;
    state.profileEditorOpen = false;
    state.profileWalletOpen = false;
    renderPage();
  };
  const openProfileWallet = async () => {
    state.profileWalletOpen = true;
    state.profileEditorOpen = false;
    state.profileRechargeOpen = false;
    renderPage();
    await refreshWalletTransactions(true);
  };
  screenEl.querySelector("#openProfileRechargeButton")?.addEventListener("click", openProfileRecharge);
  screenEl.querySelector("#openProfileWalletButton")?.addEventListener("click", openProfileWallet);
  screenEl.querySelector("#openProfileEditorButton")?.addEventListener("click", openProfileEditor);
  screenEl.querySelector("#profileCardRechargeButton")?.addEventListener("click", openProfileRecharge);
  screenEl.querySelector("#profileCardEditButton")?.addEventListener("click", openProfileEditor);
  screenEl.querySelector("#closeProfileEditorButton")?.addEventListener("click", () => {
    state.profileEditorOpen = false;
    renderPage();
  });
  screenEl.querySelector("#profileEditorBackdrop")?.addEventListener("click", (event) => {
    if (event.target.id !== "profileEditorBackdrop") {
      return;
    }
    state.profileEditorOpen = false;
    renderPage();
  });
  screenEl.querySelector("#closeProfileRechargeButton")?.addEventListener("click", () => {
    state.profileRechargeOpen = false;
    renderPage();
  });
  screenEl.querySelector("#profileRechargeBackdrop")?.addEventListener("click", (event) => {
    if (event.target.id !== "profileRechargeBackdrop") {
      return;
    }
    state.profileRechargeOpen = false;
    renderPage();
  });
  screenEl.querySelector("#closeProfileWalletButton")?.addEventListener("click", () => {
    state.profileWalletOpen = false;
    renderPage();
  });
  screenEl.querySelector("#profileWalletBackdrop")?.addEventListener("click", (event) => {
    if (event.target.id !== "profileWalletBackdrop") {
      return;
    }
    state.profileWalletOpen = false;
    renderPage();
  });
  screenEl.querySelectorAll("[data-profile-history-tab]").forEach((button) => {
    button.addEventListener("click", () => {
      state.profileHistoryTab = button.dataset.profileHistoryTab;
      renderPage();
    });
  });
  screenEl.querySelectorAll(".history-room-item[data-room-id]").forEach((button) => {
    button.addEventListener("click", () => {
      state.activeTab = "home";
      openRoom(button.dataset.roomId);
    });
  });
  screenEl.querySelector("#rechargeForm")?.addEventListener("submit", handleRecharge);
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

function renderProfileHistorySection({ title, description, rooms, loading, emptyMessage }) {
  return `
    <section class="profile-history-section">
      <div class="section-header compact">
        <div>
          <h2>${title}</h2>
          <p>${description}</p>
        </div>
      </div>
      <div class="history-room-list">
        ${
          loading
            ? `<div class="empty-card">正在加载这部分历史记录...</div>`
            : rooms.length
              ? rooms.map(renderHistoryRoomItem).join("")
              : `<div class="empty-card">${emptyMessage}</div>`
        }
      </div>
    </section>
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
  if (isRoomBiddingClosed(room) || !canCurrentUserBid(room)) {
    return;
  }

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

function syncBidPanelState() {
  const room = state.selectedRoom;
  const bidForm = document.querySelector("#bidForm");
  if (!room || !bidForm) {
    return;
  }

  const bidClosed = isRoomBiddingClosed(room);
  const qualificationPending = roomRequiresQualification(room) && !state.selectedRoomQualification;
  const qualificationBlocked = roomRequiresQualification(room) && !qualificationPending && !canCurrentUserBid(room);
  const bidDisabled = bidClosed || qualificationPending || qualificationBlocked || state.bidSubmitting;
  const amountInput = document.querySelector("#bidAmountInput");
  const adjustButtons = document.querySelectorAll("[data-bid-adjust]");
  const submitButtons = bidForm.querySelectorAll('button[type="submit"]');

  if (submitButtons.length > 1) {
    submitButtons.forEach((button, index) => {
      if (index < submitButtons.length - 1) {
        button.remove();
      }
    });
  }

  const submitButton = bidForm.querySelector('button[type="submit"]');
  if (amountInput) {
    amountInput.disabled = bidDisabled;
  }

  adjustButtons.forEach((button) => {
    button.disabled = bidDisabled;
  });

  if (submitButton) {
    submitButton.disabled = bidDisabled;
    if (bidClosed) {
      submitButton.textContent = "竞拍已结束";
    } else if (state.bidSubmitting) {
      submitButton.textContent = "正在出价...";
    } else if (qualificationPending) {
      submitButton.textContent = "资格校验中";
    } else if (qualificationBlocked) {
      submitButton.textContent = "先报名再出价";
    } else {
      submitButton.textContent = "立即出价";
    }
  }
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

  syncBidPanelState();
}

async function loadRooms() {
  state.rooms = mergeRoomsByVersion(state.rooms, await fetchRooms());

  if (state.selectedRoomId) {
    const stillExists = state.rooms.some((room) => room.roomId === state.selectedRoomId);
    if (!stillExists) {
      clearSelectedRoom();
    }
  }
}

async function loadUsers() {
  state.users = await fetchUsers();
  syncCurrentUser();
}

async function loadSelectedRoom(roomId) {
  const currentUser = getCurrentUser();
  const [room, leaderboard, qualification] = await Promise.all([
    fetchRoom(roomId),
    fetchLeaderboard(roomId),
    currentUser ? fetchQualification(roomId, currentUser.account) : Promise.resolve(null),
  ]);
  applySelectedRoom(room);
  upsertRoom(room);
  applySelectedRoomLeaderboard(leaderboard, getRoomVersion(room));
  state.selectedRoomQualification = qualification;
  syncSelectedRoomRealtime();
}

async function openRoom(roomId) {
  try {
    state.selectedRoomId = roomId;
    state.selectedRoomQualification = null;
    await loadSelectedRoom(roomId);
    renderPage();
  } catch (error) {
    setFeedback(error.message, true);
  }
}

async function handleCreateRoom(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const currentUser = getCurrentUser();
  const formData = new FormData(form);
  const payload = Object.fromEntries(formData.entries());
  payload.anchorName = payload.anchorName || currentUser?.nickname || "匿名主播";
  payload.startPrice = Number(payload.startPrice);
  payload.stepPrice = Number(payload.stepPrice);
  payload.registrationRequired = formData.get("registrationRequired") === "on";
  payload.depositAmount = payload.registrationRequired
    ? Number(payload.depositAmount || 0)
    : 0;
  payload.durationSeconds = Number(payload.durationSeconds);

  try {
    const room = await createRoom(payload);
    form.reset();
    form.elements.anchorName.value = currentUser?.nickname || "";
    form.elements.imageUrl.value = "";
    form.elements.registrationRequired.checked = true;
    form.elements.depositAmount.value = "99.00";
    state.draftRoomImageUrl = "";
    syncPublishDepositFieldState(form);
    setFeedback(`已发布房间 ${room.roomId}`);
    state.activeTab = "home";
    await loadRooms();
    await openRoom(room.roomId);
  } catch (error) {
    setFeedback(error.message, true);
  }
}

async function handleRegisterForAuction() {
  if (!state.selectedRoomId || !state.selectedRoom) {
    setFeedback("\u8bf7\u5148\u8fdb\u5165\u4e00\u4e2a\u62cd\u5356\u623f\u95f4", true);
    return;
  }

  const currentUser = getCurrentUser();
  if (!currentUser) {
    setFeedback("\u8bf7\u5148\u5728\u201c\u6211\u7684\u201d\u91cc\u9009\u62e9\u4e00\u4e2a\u8d26\u53f7", true);
    return;
  }

  if (isRoomBiddingClosed(state.selectedRoom)) {
    setFeedback("\u5f53\u524d\u623f\u95f4\u5df2\u7ed3\u675f\uff0c\u4e0d\u80fd\u518d\u62a5\u540d\u7ade\u62cd", true);
    return;
  }

  try {
    await registerForAuction(state.selectedRoomId, {
      userId: currentUser.account,
      nickname: currentUser.nickname,
    });
    await refreshCurrentUser();
    state.selectedRoomQualification = await fetchQualification(state.selectedRoomId, currentUser.account);
    setFeedback("\u62a5\u540d\u6210\u529f\uff0c\u5f53\u524d\u8d26\u53f7\u5df2\u51bb\u7ed3\u4fdd\u8bc1\u91d1\uff0c\u53ef\u4ee5\u76f4\u63a5\u51fa\u4ef7");
    renderPage();
  } catch (error) {
    setFeedback(error.message, true);
  }
}

async function handleRoomCoverSelected(event) {
  const currentUser = getCurrentUser();
  const file = event.target.files?.[0];

  if (!currentUser || !file) {
    return;
  }

  if (!file.type.startsWith("image/")) {
    setFeedback("请选择图片文件作为房间封面", true);
    event.target.value = "";
    return;
  }

  if (file.size > 8 * 1024 * 1024) {
    setFeedback("房间封面不能超过 8MB", true);
    event.target.value = "";
    return;
  }

  try {
    const uploadPolicy = await createRoomCoverUploadPolicy({
      userId: currentUser.userId,
      fileName: file.name,
      contentType: file.type,
    });
    state.draftRoomImageUrl = await uploadAvatarToOss(file, uploadPolicy);
    screenEl.querySelector('input[name="imageUrl"]')?.setAttribute("value", state.draftRoomImageUrl);
    const hiddenImageInput = screenEl.querySelector('input[name="imageUrl"]');
    if (hiddenImageInput) {
      hiddenImageInput.value = state.draftRoomImageUrl;
    }
    screenEl.querySelector("#roomCoverPreview")?.setAttribute("src", state.draftRoomImageUrl);
    setFeedback("\u623f\u95f4\u5c01\u9762\u5df2\u4e0a\u4f20");
  } catch (error) {
    setFeedback(error.message, true);
  } finally {
    event.target.value = "";
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
    invalidateUserAuctionHistory();

    if (state.selectedRoom && state.selectedRoom.leaderNickname === currentUser.nickname) {
      state.selectedRoom.leaderNickname = updatedUser.nickname;
    }

    state.rooms = state.rooms.map((room) => ({
      ...room,
      leaderNickname: room.leaderNickname === currentUser.nickname ? updatedUser.nickname : room.leaderNickname,
      anchorName: room.anchorName === currentUser.nickname ? updatedUser.nickname : room.anchorName,
    }));

    setFeedback("\u4e2a\u4eba\u8d44\u6599\u5df2\u4fdd\u5b58");
    state.profileEditorOpen = false;
    renderPage();
    if (state.activeTab === "profile") {
      refreshUserAuctionHistory(true);
    }
  } catch (error) {
    setFeedback(error.message, true);
  }
}

async function handleRecharge(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const currentUser = getCurrentUser();
  if (!currentUser) {
    setFeedback("当前没有可充值的用户", true);
    return;
  }

  const formData = new FormData(form);
  const payload = Object.fromEntries(formData.entries());
  payload.amount = Number(payload.amount);

  try {
    const updatedUser = await rechargeUser(currentUser.userId, payload);
    state.users = state.users.map((user) => (user.userId === updatedUser.userId ? updatedUser : user));
    syncCurrentUser();
    form.reset();
    state.profileRechargeOpen = false;
    invalidateWalletTransactions();
    setFeedback(`充值成功，当前可用余额 ${formatPrice(updatedUser.balance)}`);
    renderPage();
    if (state.activeTab === "profile") {
      refreshWalletTransactions(true);
    }
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
    setFeedback("\u5934\u50cf\u5df2\u66f4\u65b0");
    renderPage();
  } catch (error) {
    setFeedback(error.message, true);
  } finally {
    event.target.value = "";
  }
}

async function handleBid(event) {
  event.preventDefault();
  if (state.bidSubmitting) {
    return;
  }
  if (!state.selectedRoomId) {
    setFeedback("\u8bf7\u5148\u8fdb\u5165\u4e00\u4e2a\u62cd\u5356\u623f\u95f4", true);
    return;
  }

  const currentUser = getCurrentUser();
  if (!currentUser) {
    setFeedback("\u8bf7\u5148\u5728\u201c\u6211\u7684\u201d\u91cc\u9009\u62e9\u4e00\u4e2a\u6f14\u793a\u8d26\u53f7", true);
    return;
  }

  const formData = new FormData(event.currentTarget);
  const payload = Object.fromEntries(formData.entries());
  payload.amount = Number(payload.amount);
  payload.requestId = createRequestId();
  payload.userId = currentUser.account;
  payload.nickname = currentUser.nickname;

  if (isRoomBiddingClosed(state.selectedRoom)) {
    setFeedback("\u672c\u573a\u7ade\u62cd\u5df2\u7ed3\u675f\uff0c\u5f53\u524d\u4e0d\u80fd\u7ee7\u7eed\u51fa\u4ef7", true);
    renderPage();
    return;
  }

  if (roomRequiresQualification(state.selectedRoom) && !state.selectedRoomQualification?.canBid) {
    setFeedback("\u8bf7\u5148\u62a5\u540d\u7ade\u62cd\u5e76\u51bb\u7ed3\u4fdd\u8bc1\u91d1\uff0c\u518d\u53c2\u4e0e\u5f53\u524d\u623f\u95f4\u51fa\u4ef7", true);
    return;
  }

  try {
    state.bidSubmitting = true;
    syncBidPanelState();
    const room = await createBid(state.selectedRoomId, payload);
    await refreshCurrentUser();
    applySelectedRoom(room);
    upsertRoom(room);
    applySelectedRoomLeaderboard(await fetchLeaderboard(state.selectedRoomId), getRoomVersion(room));
    setFeedback(`出价成功，当前领先者：${room.leaderNickname}`);
    renderPage();
  } catch (error) {
    setFeedback(error.message, true);
  } finally {
    state.bidSubmitting = false;
    syncBidPanelState();
  }
}

async function handleDeleteRoom(roomId) {
  if (!window.confirm(`确认删除已结束的房间 ${roomId} 吗？`)) {
    return;
  }

  try {
    await deleteRoom(roomId);
    clearSelectedRoom();
    await loadRooms();
    state.activeTab = "home";
    setFeedback(`已删除房间 ${roomId}`);
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

async function refreshRoomsSilently() {
  try {
    await Promise.all([loadRooms(), loadUsers()]);

    if (state.activeTab !== "home") {
      return;
    }

    if (state.selectedRoomId) {
      await loadSelectedRoom(state.selectedRoomId);
    }

    renderPage();
  } catch {
    // Keep the current UI when background refresh fails.
  }
}

bottomNavItems.forEach((item) => {
  item.addEventListener("click", async () => {
    if (!item.dataset.tab) {
      return;
    }
    state.activeTab = item.dataset.tab;
    state.profileEditorOpen = false;
    state.profileRechargeOpen = false;
    state.profileWalletOpen = false;
    if (item.dataset.tab !== "home") {
      clearSelectedRoom();
    }
    renderPage();
    if (item.dataset.tab === "profile") {
      await refreshUserAuctionHistory(true);
    }
  });
});

bindPlaceholderButtons();

state.socket = createAuctionSocket({
  onRoomMessage(room) {
    if (room.roomId === state.selectedRoomId) {
      const applied = applySelectedRoom(room);
      upsertRoom(room);
      syncSelectedRoomRealtime();
      if (applied) {
        renderPage();
      }
    }
  },
  onLeaderboardMessage(roomId, payload) {
    if (roomId === state.selectedRoomId) {
      const applied = applySelectedRoomLeaderboard(payload.leaderboard, payload.version);
      if (applied) {
        renderPage();
      }
    }
  },
});

bootstrap();

setInterval(() => {
  const selectedRoomWasOpen = state.selectedRoom ? !isRoomBiddingClosed(state.selectedRoom) : false;

  if (state.selectedRoom) {
    state.selectedRoom.secondsRemaining = Math.max(0, state.selectedRoom.secondsRemaining - 1);
  }

  state.rooms = state.rooms.map((room) => ({
    ...room,
    secondsRemaining: Math.max(0, room.secondsRemaining - 1),
  }));

  updateCountdownDisplay();

  if (state.selectedRoom && selectedRoomWasOpen && isRoomBiddingClosed(state.selectedRoom)) {
    renderPage();
  }
}, 1000);

setInterval(() => {
  refreshRoomsSilently();
}, 10000);

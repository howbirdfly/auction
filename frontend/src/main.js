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
  fetchUsers,
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
  { icon: "馃崝", label: "鍚冨枬鐜╀箰" },
  { icon: "馃摫", label: "鎵嬫満鏁扮爜" },
  { icon: "鈾伙笍", label: "涓婇棬鍥炴敹" },
  { icon: "馃彔", label: "浜屾墜濂界墿" },
];

const state = {
  rooms: [],
  users: [],
  currentUserId: localStorage.getItem(CURRENT_USER_STORAGE_KEY) || null,
  currentUser: null,
  selectedRoomId: null,
  selectedRoom: null,
  selectedRoomLeaderboard: [],
  selectedRoomQualification: null,
  socket: null,
  activeTab: "home",
  activeChannel: "鎺ㄨ崘",
  feedback: "",
  feedbackError: false,
  draftRoomImageUrl: "",
};

const app = document.querySelector("#app");

app.innerHTML = `
  <div class="app-shell">
    <main id="screen" class="screen"></main>
    <section id="feedbackBanner" class="feedback-banner"></section>
    <nav class="bottom-nav">
      <button class="bottom-nav-item active" data-tab="home">
        <span class="bottom-nav-icon">馃彔</span>
        <strong>棣栭〉</strong>
      </button>
      <button class="bottom-nav-item publish-entry" data-tab="publish">
        <span class="publish-entry-badge">馃摲</span>
        <strong>鍙戝竷</strong>
      </button>
      <button class="bottom-nav-item" data-tab="profile">
        <span class="bottom-nav-icon">馃檪</span>
        <strong>鎴戠殑</strong>
      </button>
    </nav>
  </div>
`;

const screenEl = document.querySelector("#screen");
const feedbackBannerEl = document.querySelector("#feedbackBanner");
const bottomNavItems = document.querySelectorAll(".bottom-nav-item");

function formatPrice(value) {
  return `楼${Number(value || 0).toFixed(2)}`;
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

function renderHomeHeader() {
  return `
    <section class="home-top">
      <div class="search-row">
        <button class="badge-button" data-placeholder="绛惧埌鍔熻兘鍚庣画鎺ュ叆">绛惧埌</button>
        <div class="search-shell">
          <span class="search-placeholder">鎼滅储鎷嶅搧銆佹埧闂存垨涓绘挱</span>
          <span class="search-actions">馃摲</span>
          <button class="search-button" data-placeholder="鎼滅储鍔熻兘鍚庣画鎺ュ叆">鎼滅储</button>
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
          <button class="category-item" data-placeholder="${category.label}鍒嗙被鍚庣画鎺ュ叆">
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
        <p>鐩存挱绔炴媿鍏堜笂鎷嶅崠鍦?/p>
        <strong>绮鹃€夋埧闂翠綆浠疯捣鎷?/strong>
      </div>
      <button class="promo-button" data-placeholder="娲诲姩浼氬満鍚庣画鎺ュ叆">鍘诲洿瑙?/button>
    </section>
  `;
}

function renderHomeStats() {
  const liveRooms = state.rooms.filter((room) => room.status === "BIDDING").length;
  const closedRooms = state.rooms.filter((room) => room.status === "CLOSED").length;

  return `
    <section class="home-stats">
      <article class="metric-card">
        <span>绔炴媿涓?/span>
        <strong>${liveRooms}</strong>
      </article>
      <article class="metric-card">
        <span>宸茬粨鏉?/span>
        <strong>${closedRooms}</strong>
      </article>
      <button class="metric-card metric-action" id="refreshRoomsButton">
        <span>鎴块棿鍒楄〃</span>
        <strong>鍒锋柊鎴块棿</strong>
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
        <p class="feed-room-note">${room.anchorName} 路 ${room.leaderNickname || "\u6682\u65e0\u9886\u5148\u8005"}</p>
        <div class="feed-room-footer">
          <span>${room.roomId}</span>
          <span>${room.bidCount} 娆″嚭浠?/span>
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
          : `<div class="empty-card">鏆傛椂杩樻病鏈夋埧闂达紝鍘烩€滃彂甯冣€濋噷鍏堝垱寤轰竴涓媿鍗栨埧闂村惂銆?/div>`
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
            <h2>绔炴媿璧勬牸</h2>
            <p>鏈埧闂村紑鍚簡鎶ュ悕绔炴媿锛屽弬涓庡嚭浠峰墠闇€瑕佸厛閿佸畾淇濊瘉閲戙€?/p>
          </div>
        </div>
        <div class="qualification-card">
          <div class="qualification-meta">
            <strong>淇濊瘉閲?${formatPrice(room.depositAmount)}</strong>
            <span>璇峰厛鍒扳€滄垜鐨勨€濋噷閫夋嫨涓€涓处鍙凤紝鍐嶅洖鏉ユ姤鍚嶇珵鎷嶃€?/span>
          </div>
        </div>
      </section>
    `;
  }

  return `
    <section class="room-panel qualification-panel">
      <div class="section-header compact">
        <div>
          <h2>绔炴媿璧勬牸</h2>
          <p>鎶ュ悕閫氳繃鍚庝細涓哄綋鍓嶈处鍙峰喕缁撲繚璇侀噾锛屾湭鎶ュ悕鐨勮处鍙蜂笉鑳界洿鎺ュ嚭浠枫€?/p>
        </div>
      </div>
      <div class="qualification-card ${isQualified ? "qualified" : ""}">
        <div class="qualification-meta">
          <strong>${statusText}</strong>
          <span>${description}</span>
        </div>
        <div class="qualification-side">
          <b>${formatPrice(qualification?.depositAmount ?? room.depositAmount)}</b>
          <small>淇濊瘉閲?/small>
        </div>
      </div>
      ${
        !bidClosed && qualification && !qualification.canBid
          ? `<button type="button" class="qualification-button" id="registerAuctionButton">鎶ュ悕绔炴媿骞跺喕缁撲繚璇侀噾</button>`
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
  const bidDisabled = bidClosed || qualificationPending || qualificationBlocked;
  const disabledAttr = bidDisabled ? "disabled" : "";

  if (!currentUser) {
    return `
      <section class="room-panel bid-panel">
        <div class="section-header compact">
          <div>
            <h2>妯℃嫙鍑轰环</h2>
            <p>璇峰厛鍘烩€滄垜鐨勨€濋噷閫夋嫨涓€涓綋鍓嶆紨绀鸿处鍙凤紝鍐嶅洖鏉ュ弬涓庡嚭浠枫€?/p>
          </div>
        </div>
      </section>
    `;
  }

  return `
    <section class="room-panel bid-panel">
      <div class="section-header compact">
        <div>
          <h2>妯℃嫙鍑轰环</h2>
          <p>褰撳墠浼氫娇鐢ㄤ綘鍦ㄢ€滄垜鐨勨€濋噷閫変腑鐨勮处鍙风洿鎺ュ嚭浠枫€?/p>
        </div>
      </div>

      <div class="bid-user-card">
        <div class="bid-user-head">
          <img class="bid-user-avatar" src="${getAvatarUrl(currentUser)}" alt="${currentUser.nickname}" />
          <div>
            <strong>${currentUser.nickname}</strong>
            <span>@${currentUser.account} 路 ${currentUser.userId}</span>
          </div>
        </div>
      </div>

      ${bidClosed ? `<div class="bid-closed-note">鏈満绔炴媿宸茬粨鏉燂紝鍙互杩斿洖棣栭〉鐪嬬湅鍏朵粬鎴块棿銆?/div>` : ""}

      ${qualificationPending ? `<div class="bid-closed-note">濮濓絽婀弽锟犵崣瑜版挸澧犵拹锕€褰块惃鍕彽閹峰秷绁弽纭风礉鐠囬鈼㈤崐娆嶁偓?/div>` : ""}
      ${qualificationBlocked ? `<div class="bid-closed-note">鐠囧嘲鍘涢幎銉ユ倳缁旂偞濯块獮璺哄枙缂佹挷绻氱拠渚€鍣鹃敍灞藉晙閸欏倷绗岃ぐ鎾冲閹村潡妫块崙杞扮幆閵?/div>` : ""}
      <form id="bidForm" class="stack-form">
        <div class="bid-amount-panel">
          <div class="bid-amount-label">
            <span>鏈鍑轰环</span>
            <strong>姣忔鍔犱环 ${formatPrice(room.stepPrice)}</strong>
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
              placeholder="鏈鍑轰环閲戦"
              value="${Number(room.currentPrice).toFixed(2)}"
              ${disabledAttr}
              required
            />
            <button type="button" class="bid-step-button" data-bid-adjust="1" ${disabledAttr}>+</button>
          </div>
        </div>
        <button type="submit" ${disabledAttr}>${bidClosed ? "\u7ade\u62cd\u5df2\u7ed3\u675f" : "\u7acb\u5373\u51fa\u4ef7"}</button>
      </form>
    </section>
  `;
}

function renderLeaderboardPanel() {
  const leaderboard = state.selectedRoomLeaderboard || [];
  const currentUser = getCurrentUser();

  return `
    <section class="room-panel">
      <div class="section-header compact">
        <div>
          <h2>瀹炴椂鎺掕</h2>
          <p>涓磋繎缁撴潫鏃讹紝杩欓噷浼氬疄鏃跺埛鏂板綋鍓嶉鍏堢殑鍑轰环鐢ㄦ埛銆?/p>
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
            : `<div class="empty-card">褰撳墠杩樻病鏈変笂姒滃嚭浠枫€?/div>`
        }
      </div>
    </section>
  `;
}

function renderSettlementPanel(room) {
  if (room.status !== "CLOSED") {
    return "";
  }

  const winner =
    state.users.find((user) => user.nickname === room.leaderNickname) ||
    state.users.find((user) => user.account === room.leaderNickname) ||
    null;
  const hasWinner = Boolean(room.leaderNickname && room.bidCount > 0);

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
              <div>
                <strong>${room.leaderNickname}</strong>
                <span>鎭枩鎷嶅緱鏈満鍟嗗搧</span>
              </div>
            </div>
          `
          : `
            <div class="settlement-empty">
              <strong>鏈満鏃犱汉鎴愪氦</strong>
              <span>鍙互璋冩暣璧锋媿浠枫€佹椂闀挎垨鍟嗗搧淇℃伅鍚庨噸鏂板紑鎷嶃€?/span>
            </div>
          `
      }

      <div class="settlement-grid">
        <article class="settlement-metric highlight">
          <span>${hasWinner ? "\u6210\u4ea4\u4ef7" : "\u7ed3\u679c"}</span>
          <strong>${hasWinner ? formatPrice(room.currentPrice) : "\u672a\u6210\u4ea4"}</strong>
        </article>
        <article class="settlement-metric">
          <span>鑾疯儨鑰?/span>
          <strong>${hasWinner ? room.leaderNickname : "鏃犱汉鍑轰环"}</strong>
        </article>
        <article class="settlement-metric">
          <span>缁撴潫鏃堕棿</span>
          <strong>${formatDateTime(room.endsAt)}</strong>
        </article>
        <article class="settlement-metric">
          <span>鎬诲嚭浠锋鏁?/span>
          <strong>${room.bidCount}</strong>
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
        <button id="backToLobby" class="back-button">杩斿洖</button>
        <div class="room-topbar-meta">
          <span class="status-pill inline ${room.status === "CLOSED" ? "closed" : ""}">
            ${room.status === "CLOSED" ? "宸茬粨鏉? : "绔炴媿涓?}
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
          <p class="eyebrow">鎷嶅崠鎴块棿</p>
          <h1>${room.itemTitle}</h1>
          <p>${room.anchorName}</p>
        </div>
      </section>

      <section class="room-summary">
        <div class="summary-card primary">
          <span>褰撳墠浠?/span>
          <strong>${formatPrice(room.currentPrice)}</strong>
        </div>
        <div class="summary-card">
          <span>涓嬩竴鍙ｈ捣鎷?/span>
          <strong>${formatPrice(room.minNextBid)}</strong>
        </div>
        <div class="summary-card">
          <span>棰嗗厛鑰?/span>
          <strong>${room.leaderNickname || "鏆傛棤"}</strong>
        </div>
        <div class="summary-card">
          <span>鍓╀綑鏃堕棿</span>
          <strong id="roomCountdownValue">${formatCountdown(room.secondsRemaining)}</strong>
        </div>
      </section>

      ${renderSettlementPanel(room)}

      ${renderLeaderboardPanel()}

      ${renderQualificationPanel(room)}

      <section class="room-panel">
        <div class="section-header compact">
          <div>
            <h2>鏈€鏂板嚭浠疯褰?/h2>
            <p>鎴块棿鍐呮墍鏈変汉閮借兘鐪嬪埌杩欓噷鐨勫疄鏃跺彉鍖栥€?/p>
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
              : `<div class="empty-card">杩樻病鏈夌敤鎴峰嚭浠凤紝褰撳墠鎴块棿姝ｅ湪绛夊緟绗竴浣嶇珵鎷嶈€呫€?/div>`
          }
        </div>
      </section>

      ${
        room.status === "CLOSED" && getCurrentUser()?.nickname === room.anchorName
          ? `
            <section class="room-panel room-danger-panel">
              <div class="section-header compact">
                <div>
                  <h2>鍒犻櫎鎴块棿</h2>
                  <p>绔炴媿缁撴潫鍚庯紝浣犲彲浠ユ妸杩欎釜鎴块棿浠庨椤靛垪琛ㄩ噷绉婚櫎銆?/p>
                </div>
              </div>
              <button type="button" class="danger-button" id="deleteExpiredRoomButton">鍒犻櫎宸茬粨鏉熸埧闂?/button>
            </section>
          `
          : ""
      }

      ${renderBidPanel(room)}
    </section>
  `;

  screenEl.querySelector("#backToLobby")?.addEventListener("click", () => {
    state.selectedRoomId = null;
    state.selectedRoom = null;
    state.selectedRoomLeaderboard = [];
    state.selectedRoomQualification = null;
    renderPage();
  });

  screenEl.querySelector("#bidForm")?.addEventListener("submit", handleBid);
  screenEl.querySelector("#registerAuctionButton")?.addEventListener("click", handleRegisterForAuction);
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
        <p class="eyebrow">CREATE ROOM</p>
        <h1>鍙戝竷绔炴媿鎴块棿</h1>
        <p class="hero-text">鎶婂垱寤烘埧闂村崟鐙斁鍦ㄨ繖閲岋紝棣栭〉璐熻矗閫涙埧闂达紝涓汉椤靛彧淇濈暀鍜岃处鍙风浉鍏崇殑鍐呭銆?/p>
      </section>

      <section class="room-panel publish-panel">
        <div class="section-header compact">
          <div>
            <h2>鍒涘缓鎷嶅崠鎴?/h2>
            <p>${currentUser ? `\u5f53\u524d\u9ed8\u8ba4\u4f7f\u7528 ${currentUser.nickname} \u4f5c\u4e3a\u4e3b\u64ad\u540d\u3002` : "\u8bf7\u5148\u53bb\u201c\u6211\u7684\u201d\u91cc\u9009\u62e9\u4e00\u4e2a\u8d26\u53f7\u540e\u518d\u53d1\u5e03\u3002"}</p>
          </div>
        </div>
        <form id="createForm" class="stack-form publish-form">
          <div class="cover-upload-card">
            <div class="cover-upload-preview">
              <img
                id="roomCoverPreview"
                src="${state.draftRoomImageUrl || DEFAULT_IMAGE}"
                alt="鎴块棿灏侀潰棰勮"
                onerror="this.src='${DEFAULT_IMAGE}'"
              />
            </div>
            <div class="cover-upload-meta">
              <strong>鎴块棿灏侀潰</strong>
              <span>寤鸿涓婁紶 1:1 鎴?4:3 鐨勫晢鍝佸浘</span>
            </div>
            <button type="button" class="ghost-button compact-button" id="uploadRoomCoverButton">涓婁紶鍥剧墖</button>
            <input id="roomCoverFileInput" type="file" accept="image/png,image/jpeg,image/webp,image/gif" hidden />
          </div>

          <input name="itemTitle" placeholder="鎷嶅搧鍚嶇О" required />
          <input name="anchorName" placeholder="涓绘挱鍚嶇О" value="${currentUser?.nickname || ""}" required />
          <input name="imageUrl" value="${state.draftRoomImageUrl}" hidden />
          <div class="compact-grid">
            <input name="startPrice" type="number" min="0.01" step="0.01" placeholder="\u8d77\u62cd\u4ef7" required />
            <input name="stepPrice" type="number" min="0.01" step="0.01" placeholder="鍔犱环骞呭害" required />
          </div>
          <input name="durationSeconds" type="number" min="30" step="1" placeholder="\u6301\u7eed\u65f6\u957f\uff08\u79d2\uff09" required />
          <button type="submit">绔嬪嵆鍙戝竷鎴块棿</button>
        </form>
      </section>
    </section>
  `;

  screenEl.querySelector("#createForm")?.addEventListener("submit", handleCreateRoom);
  screenEl.querySelector("#uploadRoomCoverButton")?.addEventListener("click", () => {
    screenEl.querySelector("#roomCoverFileInput")?.click();
  });
  screenEl.querySelector("#roomCoverFileInput")?.addEventListener("change", handleRoomCoverSelected);
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
          <h1>涓汉涓婚〉</h1>
          <p>鍚庣鐢ㄦ埛鎺ュ彛宸茬粡鎺ュソ浜嗭紝浣嗗綋鍓嶈繕娌℃湁鍙敤璐﹀彿銆?/p>
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
            <h1>涓汉涓婚〉</h1>
            <p class="profile-account">杩欓噷鍙睍绀鸿处鍙疯祫鏂欍€佹垜鍒涘缓鐨勬埧闂村拰鎴戝綋鍓嶉鍏堢殑绔炴媿銆?/p>
          </div>
        </div>

        <div class="profile-overview-actions">
          <label class="profile-account-switcher">
            <span>褰撳墠璐﹀彿</span>
            <select id="currentUserSelect">
              ${state.users
                .map(
                  (user) => `
                    <option value="${user.userId}" ${user.userId === currentUser.userId ? "selected" : ""}>
                      ${user.nickname} 路 @${user.account}
                    </option>
                  `,
                )
                .join("")}
            </select>
          </label>
          <button class="ghost-button" id="jumpToEditor">缂栬緫璧勬枡</button>
        </div>
      </section>

      <section class="profile-card user-profile-card">
        <div class="user-profile-head">
          <div class="profile-avatar-wrap">
            <img class="profile-avatar" src="${getAvatarUrl(currentUser)}" alt="${currentUser.nickname}" />
            <button type="button" class="profile-avatar-button" id="changeAvatarButton">鏇存崲澶村儚</button>
            <input id="avatarFileInput" type="file" accept="image/png,image/jpeg,image/webp,image/gif" hidden />
          </div>
          <div>
            <p class="eyebrow">MY AUCTION PROFILE</p>
            <h1>${currentUser.nickname}</h1>
            <p class="profile-account">@${currentUser.account} 路 ${currentUser.userId}</p>
          </div>
        </div>
        <p class="profile-bio">${currentUser.bio || "\u8fd9\u4e2a\u7528\u6237\u8fd8\u6ca1\u6709\u5199\u7b80\u4ecb\u3002"}</p>
      </section>

      <section class="profile-metrics">
        <div class="metric-card">
          <span>鎴戝垱寤虹殑鎴块棿</span>
          <strong>${createdRooms.length}</strong>
        </div>
        <div class="metric-card">
          <span>褰撳墠棰嗗厛涓?/span>
          <strong>${leadingRooms.length}</strong>
        </div>
        <div class="metric-card">
          <span>绔炴媿涓埧闂?/span>
          <strong>${getLiveRooms().length}</strong>
        </div>
        <div class="metric-card">
          <span>鍔犲叆鏃堕棿</span>
          <strong>${formatShortTime(currentUser.createdAt)}</strong>
        </div>
      </section>

      <section class="room-panel">
        <div class="section-header compact">
          <div>
            <h2>鎴戝垱寤虹殑鎴块棿</h2>
            <p>杩欓噷浼氬睍绀轰綘鍙戝竷杩囩殑绔炴媿鎴块棿銆?/p>
          </div>
        </div>
        <div class="history-room-list">
          ${
            createdRooms.length
              ? createdRooms.map(renderHistoryRoomItem).join("")
              : `<div class="empty-card">浣犺繕娌℃湁鍒涘缓鎴块棿锛屽幓搴曢儴鈥滃彂甯冣€濊瘯璇曞惂銆?/div>`
          }
        </div>
      </section>

      <section class="room-panel">
        <div class="section-header compact">
          <div>
            <h2>鎴戝綋鍓嶉鍏?/h2>
            <p>杩欓噷浼氬睍绀轰綘鐩墠鎺掑湪绗竴鐨勭珵鎷嶆埧闂淬€?/p>
          </div>
        </div>
        <div class="history-room-list">
          ${
            leadingRooms.length
              ? leadingRooms.map(renderHistoryRoomItem).join("")
              : `<div class="empty-card">浣犲綋鍓嶈繕娌℃湁棰嗗厛涓殑鎴块棿銆?/div>`
          }
        </div>
      </section>

      <section class="room-panel">
        <div id="profileEditorAnchor"></div>
        <div class="section-header compact">
          <div>
            <h2>缂栬緫璧勬枡</h2>
            <p>澶村儚寤鸿鐢ㄤ笂闈㈢殑鎸夐挳鐩存帴涓婁紶锛岃繖閲屼富瑕佷慨鏀规樀绉般€佺畝浠嬪拰瀵嗙爜銆?/p>
          </div>
        </div>
        <form id="profileForm" class="stack-form">
          <input name="nickname" placeholder="鏄电О" value="${currentUser.nickname}" required />
          <input name="avatarUrl" placeholder="闇€瑕佹墜鍔ㄦ浛鎹㈡椂鍐嶅～鍐欐柊鐨勫ご鍍忓湴鍧€" />
          <textarea name="bio" rows="4" placeholder="\u4e2a\u4eba\u7b80\u4ecb">${currentUser.bio || ""}</textarea>
          <input name="password" type="password" placeholder="鏂板瘑鐮侊紝涓嶄慨鏀瑰彲鐣欑┖" />
          <button type="submit">淇濆瓨璧勬枡</button>
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
        <p>${room.anchorName} 路 ${room.roomId}</p>
        <div class="history-room-meta">
          <span>${room.bidCount} 娆″嚭浠?/span>
          <span>${room.status === "CLOSED" ? "宸茬粨鏉? : "绔炴媿涓?}</span>
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
  const bidDisabled = bidClosed || qualificationPending || qualificationBlocked;
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
    if (qualificationPending) {
      submitButton.textContent = "\u8d44\u683c\u6821\u9a8c\u4e2d";
    } else if (qualificationBlocked) {
      submitButton.textContent = "\u5148\u62a5\u540d\u518d\u51fa\u4ef7";
    }
    submitButton.textContent = bidClosed ? "\u7ade\u62cd\u5df2\u7ed3\u675f" : "\u7acb\u5373\u51fa\u4ef7";
  }
  if (submitButton && qualificationPending) {
    submitButton.textContent = "\u8d44\u683c\u6821\u9a8c\u4e2d";
  } else if (submitButton && qualificationBlocked) {
    submitButton.textContent = "\u5148\u62a5\u540d\u518d\u51fa\u4ef7";
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
  state.rooms = sortRooms(await fetchRooms());

  if (state.selectedRoomId) {
    const stillExists = state.rooms.some((room) => room.roomId === state.selectedRoomId);
    if (!stillExists) {
      state.selectedRoomId = null;
      state.selectedRoom = null;
      state.selectedRoomLeaderboard = [];
      state.selectedRoomQualification = null;
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
  state.selectedRoom = room;
  state.selectedRoomLeaderboard = leaderboard;
  state.selectedRoomQualification = qualification;
}

async function openRoom(roomId) {
  /*
    removed broken feedback line during qualification cleanup
    return;
  */

  try {
    state.selectedRoomId = roomId;
    state.selectedRoomQualification = null;
    await loadSelectedRoom(roomId);
    state.socket?.subscribeRoom(roomId);
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
  payload.anchorName = payload.anchorName || currentUser?.nickname || "鍖垮悕涓绘挱";
  payload.startPrice = Number(payload.startPrice);
  payload.stepPrice = Number(payload.stepPrice);
  payload.durationSeconds = Number(payload.durationSeconds);

  try {
    const room = await createRoom(payload);
    form.reset();
    form.elements.anchorName.value = currentUser?.nickname || "";
    form.elements.imageUrl.value = "";
    state.draftRoomImageUrl = "";
    setFeedback(`宸插彂甯冩埧闂?${room.roomId}`);
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
    setFeedback("璇烽€夋嫨鍥剧墖鏂囦欢浣滀负鎴块棿灏侀潰", true);
    event.target.value = "";
    return;
  }

  if (file.size > 8 * 1024 * 1024) {
    setFeedback("鎴块棿灏侀潰涓嶈兘瓒呰繃 8MB", true);
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
    setFeedback("褰撳墠娌℃湁鍙紪杈戠殑鐢ㄦ埛", true);
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

    setFeedback("\u4e2a\u4eba\u8d44\u6599\u5df2\u4fdd\u5b58");
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
    setFeedback("璇烽€夋嫨鍥剧墖鏂囦欢浣滀负澶村儚", true);
    event.target.value = "";
    return;
  }

  if (file.size > 5 * 1024 * 1024) {
    setFeedback("澶村儚鍥剧墖涓嶈兘瓒呰繃 5MB", true);
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
    const room = await createBid(state.selectedRoomId, payload);
    state.selectedRoom = room;
    state.selectedRoomLeaderboard = await fetchLeaderboard(state.selectedRoomId);
    state.rooms = state.rooms.map((item) => (item.roomId === room.roomId ? room : item));
    setFeedback(`鍑轰环鎴愬姛锛屽綋鍓嶉鍏堣€咃細${room.leaderNickname}`);
    renderPage();
  } catch (error) {
    setFeedback(error.message, true);
  }
}

async function handleDeleteRoom(roomId) {
  if (!window.confirm(`纭鍒犻櫎宸茬粨鏉熺殑鎴块棿 ${roomId} 鍚楋紵`)) {
    return;
  }

  try {
    await deleteRoom(roomId);
    state.selectedRoomId = null;
    state.selectedRoom = null;
    state.selectedRoomLeaderboard = [];
    state.selectedRoomQualification = null;
    await loadRooms();
    state.activeTab = "home";
    setFeedback(`宸插垹闄ゆ埧闂?${roomId}`);
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
    await loadRooms();

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
  item.addEventListener("click", () => {
    if (!item.dataset.tab) {
      return;
    }
    state.activeTab = item.dataset.tab;
    if (item.dataset.tab !== "home") {
      state.selectedRoomId = null;
      state.selectedRoom = null;
      state.selectedRoomLeaderboard = [];
      state.selectedRoomQualification = null;
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

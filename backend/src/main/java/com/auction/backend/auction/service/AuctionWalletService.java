package com.auction.backend.auction.service;

import com.auction.backend.auction.mapper.AuctionRoomRegistrationMapper;
import com.auction.backend.auction.model.AuctionRegistrationStatus;
import com.auction.backend.auction.model.AuctionRoomRegistration;
import com.auction.backend.user.mapper.UserAccountMapper;
import com.auction.backend.user.model.UserAccount;
import com.auction.backend.user.service.HotWalletCacheService;
import com.auction.backend.user.service.WalletTransactionService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Service
public class AuctionWalletService {

    private final UserAccountMapper userAccountMapper;
    private final AuctionRoomRegistrationMapper auctionRoomRegistrationMapper;
    private final HotWalletCacheService hotWalletCacheService;
    private final WalletTransactionService walletTransactionService;

    public AuctionWalletService(UserAccountMapper userAccountMapper,
                                AuctionRoomRegistrationMapper auctionRoomRegistrationMapper,
                                HotWalletCacheService hotWalletCacheService,
                                WalletTransactionService walletTransactionService) {
        this.userAccountMapper = userAccountMapper;
        this.auctionRoomRegistrationMapper = auctionRoomRegistrationMapper;
        this.hotWalletCacheService = hotWalletCacheService;
        this.walletTransactionService = walletTransactionService;
    }

    @Transactional(readOnly = true)
    public void assertCanReserveBid(String bidderAccount,
                                    BigDecimal bidAmount,
                                    String previousLeaderAccount,
                                    BigDecimal previousAmount) {
        UserAccount userAccount = findUserByAccount(bidderAccount);
        BigDecimal required = bidderAccount.equals(previousLeaderAccount)
                ? bidAmount.subtract(safeAmount(previousAmount))
                : bidAmount;
        if (required.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        if (userAccount.getBalance().compareTo(required) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "余额不足，请先充值后再出价");
        }
    }

    @Transactional
    public void lockDeposit(String account, BigDecimal amount, String roomId) {
        if (safeAmount(amount).compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        UserAccount userAccount = loadWalletForMutation(account);
        ensureAvailableBalance(userAccount, amount, "余额不足，无法冻结保证金");
        userAccount.setBalance(userAccount.getBalance().subtract(amount));
        userAccount.setFrozenAmount(userAccount.getFrozenAmount().add(amount));
        userAccount.setUpdatedAt(Instant.now());
        persistWallet(userAccount, true);
        walletTransactionService.record(
                userAccount,
                "DEPOSIT_LOCK",
                amount.negate(),
                amount,
                "AUCTION_ROOM",
                roomId,
                "报名竞拍并冻结保证金",
                "DEPOSIT_LOCK:" + roomId + ":" + account + ":" + normalizeAmount(amount)
        );
    }

    @Transactional
    public void releaseDeposit(String account, BigDecimal amount, String roomId) {
        releaseFunds(
                account,
                amount,
                true,
                true,
                "DEPOSIT_RELEASE",
                "AUCTION_ROOM",
                roomId,
                "报名保证金释放",
                "DEPOSIT_RELEASE:" + roomId + ":" + account + ":" + normalizeAmount(amount)
        );
    }

    @Transactional
    public void reserveBid(String bidderAccount,
                           BigDecimal bidAmount,
                           String previousLeaderAccount,
                           BigDecimal previousAmount,
                           String roomId,
                           String requestId) {
        BigDecimal safePreviousAmount = safeAmount(previousAmount);
        if (previousLeaderAccount != null
                && !previousLeaderAccount.isBlank()
                && !previousLeaderAccount.equals(bidderAccount)
                && safePreviousAmount.compareTo(BigDecimal.ZERO) > 0) {
            releaseFunds(
                    previousLeaderAccount,
                    safePreviousAmount,
                    true,
                    true,
                    "OUTBID_RELEASE",
                    "AUCTION_ROOM",
                    roomId,
                    "被反超后释放冻结出价金额",
                    "OUTBID_RELEASE:" + roomId + ":" + requestId + ":" + previousLeaderAccount
            );
        }

        UserAccount bidderAccountRow = loadWalletForMutation(bidderAccount);
        BigDecimal required = bidderAccount.equals(previousLeaderAccount)
                ? bidAmount.subtract(safePreviousAmount)
                : bidAmount;
        if (required.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        ensureAvailableBalance(bidderAccountRow, required, "余额不足，请先充值后再出价");
        bidderAccountRow.setBalance(bidderAccountRow.getBalance().subtract(required));
        bidderAccountRow.setFrozenAmount(bidderAccountRow.getFrozenAmount().add(required));
        bidderAccountRow.setUpdatedAt(Instant.now());
        persistWallet(bidderAccountRow, true);
        walletTransactionService.record(
                bidderAccountRow,
                "BID_FREEZE",
                required.negate(),
                required,
                "AUCTION_ROOM",
                roomId,
                "出价后冻结领先金额",
                "BID_FREEZE:" + roomId + ":" + requestId + ":" + bidderAccount
        );
    }

    @Transactional
    public void applyHotBidReservation(String bidderAccount,
                                       BigDecimal bidAmount,
                                       String previousLeaderAccount,
                                       BigDecimal previousAmount,
                                       String roomId,
                                       String requestId) {
        BigDecimal safePreviousAmount = safeAmount(previousAmount);
        if (previousLeaderAccount != null
                && !previousLeaderAccount.isBlank()
                && !previousLeaderAccount.equals(bidderAccount)
                && safePreviousAmount.compareTo(BigDecimal.ZERO) > 0) {
            releaseFunds(
                    previousLeaderAccount,
                    safePreviousAmount,
                    false,
                    false,
                    "OUTBID_RELEASE",
                    "AUCTION_ROOM",
                    roomId,
                    "被反超后释放冻结出价金额",
                    "OUTBID_RELEASE:" + roomId + ":" + requestId + ":" + previousLeaderAccount
            );
        }

        UserAccount bidderAccountRow = findUserByAccountForUpdate(bidderAccount);
        BigDecimal required = bidderAccount.equals(previousLeaderAccount)
                ? bidAmount.subtract(safePreviousAmount)
                : bidAmount;
        if (required.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        bidderAccountRow.setBalance(bidderAccountRow.getBalance().subtract(required));
        bidderAccountRow.setFrozenAmount(bidderAccountRow.getFrozenAmount().add(required));
        bidderAccountRow.setUpdatedAt(Instant.now());
        persistWallet(bidderAccountRow, false);
        walletTransactionService.record(
                bidderAccountRow,
                "BID_FREEZE",
                required.negate(),
                required,
                "AUCTION_ROOM",
                roomId,
                "出价后冻结领先金额",
                "BID_FREEZE:" + roomId + ":" + requestId + ":" + bidderAccount
        );
    }

    @Transactional
    public void settleRoom(String roomId, String winnerAccount, BigDecimal finalPrice) {
        List<AuctionRoomRegistration> registrations = auctionRoomRegistrationMapper.findAllByRoomId(roomId);
        syncAccountsToMySql(registrations.stream().map(AuctionRoomRegistration::getUserId).toList());

        boolean winnerHadLockedRegistration = winnerAccount != null
                && registrations.stream().anyMatch(registration ->
                registration.getUserId().equals(winnerAccount)
                        && registration.getStatus() == AuctionRegistrationStatus.LOCKED
        );

        if (winnerHadLockedRegistration && safeAmount(finalPrice).compareTo(BigDecimal.ZERO) > 0) {
            consumeWinningBidOnSettlement(winnerAccount, finalPrice, roomId);
        }

        Instant now = Instant.now();
        for (AuctionRoomRegistration registration : registrations) {
            if (registration.getStatus() != AuctionRegistrationStatus.LOCKED) {
                continue;
            }

            releaseDepositOnSettlement(registration.getUserId(), registration.getDepositAmount(), roomId);
            registration.setStatus(AuctionRegistrationStatus.RELEASED);
            registration.setUpdatedAt(now);
            auctionRoomRegistrationMapper.updateForRegistration(registration);
        }
    }

    @Transactional
    public void syncAccountsToMySql(List<String> accounts) {
        hotWalletCacheService.syncAccountsToMySql(accounts);
    }

    @Transactional
    public void consumeWinningBidOnSettlement(String account, BigDecimal amount, String roomId) {
        consumeFrozenFunds(
                account,
                amount,
                false,
                true,
                "BID_SETTLEMENT",
                "AUCTION_ROOM",
                roomId,
                "竞拍成交后扣除最终成交价",
                "BID_SETTLEMENT:" + roomId + ":" + account
        );
    }

    @Transactional
    public void releaseDepositOnSettlement(String account, BigDecimal amount, String roomId) {
        releaseFunds(
                account,
                amount,
                false,
                true,
                "DEPOSIT_SETTLEMENT_RELEASE",
                "AUCTION_ROOM",
                roomId,
                "结算完成后退回报名保证金",
                "DEPOSIT_SETTLEMENT_RELEASE:" + roomId + ":" + account
        );
    }

    private void releaseFunds(String account,
                              BigDecimal amount,
                              boolean useHotCache,
                              boolean syncRedis,
                              String transactionType,
                              String referenceType,
                              String referenceId,
                              String description,
                              String businessKey) {
        if (safeAmount(amount).compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        UserAccount userAccount = useHotCache
                ? loadWalletForMutation(account)
                : findUserByAccountForUpdate(account);
        BigDecimal releasable = userAccount.getFrozenAmount().min(amount);
        if (releasable.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        userAccount.setFrozenAmount(userAccount.getFrozenAmount().subtract(releasable));
        userAccount.setBalance(userAccount.getBalance().add(releasable));
        userAccount.setUpdatedAt(Instant.now());
        persistWallet(userAccount, syncRedis);
        walletTransactionService.record(
                userAccount,
                transactionType,
                releasable,
                releasable.negate(),
                referenceType,
                referenceId,
                description,
                businessKey
        );
    }

    private void consumeFrozenFunds(String account,
                                    BigDecimal amount,
                                    boolean useHotCache,
                                    boolean syncRedis,
                                    String transactionType,
                                    String referenceType,
                                    String referenceId,
                                    String description,
                                    String businessKey) {
        if (safeAmount(amount).compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        UserAccount userAccount = useHotCache
                ? loadWalletForMutation(account)
                : findUserByAccountForUpdate(account);
        BigDecimal consumable = userAccount.getFrozenAmount().min(amount);
        if (consumable.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        userAccount.setFrozenAmount(userAccount.getFrozenAmount().subtract(consumable));
        userAccount.setUpdatedAt(Instant.now());
        persistWallet(userAccount, syncRedis);
        walletTransactionService.record(
                userAccount,
                transactionType,
                BigDecimal.ZERO,
                consumable.negate(),
                referenceType,
                referenceId,
                description,
                businessKey
        );
    }

    private void ensureAvailableBalance(UserAccount userAccount, BigDecimal amount, String message) {
        if (userAccount.getBalance().compareTo(amount) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }

    private UserAccount findUserByAccount(String account) {
        UserAccount userAccount = userAccountMapper.findByAccount(account);
        if (userAccount == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user account not found");
        }
        initializeWalletIfNeeded(userAccount);
        return userAccount;
    }

    private UserAccount findUserByAccountForUpdate(String account) {
        UserAccount userAccount = userAccountMapper.findByAccountForUpdate(account);
        if (userAccount == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user account not found");
        }
        initializeWalletIfNeeded(userAccount);
        return userAccount;
    }

    private UserAccount loadWalletForMutation(String account) {
        hotWalletCacheService.syncAccountsToMySql(List.of(account));
        return findUserByAccountForUpdate(account);
    }

    private void persistWallet(UserAccount userAccount, boolean syncRedis) {
        userAccountMapper.updateWallet(userAccount);
        if (syncRedis) {
            hotWalletCacheService.syncAuthoritativeWallet(userAccount);
        }
    }

    private void initializeWalletIfNeeded(UserAccount userAccount) {
        if (userAccount.getBalance() == null) {
            userAccount.setBalance(BigDecimal.ZERO);
        }
        if (userAccount.getFrozenAmount() == null) {
            userAccount.setFrozenAmount(BigDecimal.ZERO);
        }
    }

    private BigDecimal safeAmount(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }

    private String normalizeAmount(BigDecimal amount) {
        return safeAmount(amount).stripTrailingZeros().toPlainString();
    }
}

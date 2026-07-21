package com.auction.backend.user.service;

import com.auction.backend.user.model.UserAccount;

import java.util.List;

public interface HotWalletCacheService {

    void prewarmAccountIfNeeded(String account);

    void prewarmAccountsIfNeeded(List<String> accounts);

    UserAccount overlayWallet(UserAccount userAccount);

    void syncAuthoritativeWallet(UserAccount userAccount);

    void syncAccountsToMySql(List<String> accounts);

    String walletKeyPrefix();

    long walletTtlSeconds();
}

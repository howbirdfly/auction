package com.auction.backend.user.service;

import com.auction.backend.user.model.UserAccount;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@ConditionalOnProperty(name = "auction.cache.redis.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpHotWalletCacheService implements HotWalletCacheService {

    @Override
    public void prewarmAccountIfNeeded(String account) {
        // Redis-backed hot wallet cache is disabled for the current environment.
    }

    @Override
    public void prewarmAccountsIfNeeded(List<String> accounts) {
        // Redis-backed hot wallet cache is disabled for the current environment.
    }

    @Override
    public UserAccount overlayWallet(UserAccount userAccount) {
        return userAccount;
    }

    @Override
    public void syncAuthoritativeWallet(UserAccount userAccount) {
        // Redis-backed hot wallet cache is disabled for the current environment.
    }

    @Override
    public void syncAccountsToMySql(List<String> accounts) {
        // Redis-backed hot wallet cache is disabled for the current environment.
    }

    @Override
    public String walletKeyPrefix() {
        return "";
    }

    @Override
    public long walletTtlSeconds() {
        return 0L;
    }
}

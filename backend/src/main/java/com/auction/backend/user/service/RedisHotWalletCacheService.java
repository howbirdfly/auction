package com.auction.backend.user.service;

import com.auction.backend.auction.config.AuctionCacheProperties;
import com.auction.backend.user.mapper.UserAccountMapper;
import com.auction.backend.user.model.UserAccount;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@ConditionalOnProperty(name = "auction.cache.redis.enabled", havingValue = "true")
public class RedisHotWalletCacheService implements HotWalletCacheService {

    private static final String WALLET_KEY_PREFIX = "auction:wallet:";

    private final StringRedisTemplate stringRedisTemplate;
    private final UserAccountMapper userAccountMapper;
    private final AuctionCacheProperties auctionCacheProperties;

    public RedisHotWalletCacheService(StringRedisTemplate stringRedisTemplate,
                                      UserAccountMapper userAccountMapper,
                                      AuctionCacheProperties auctionCacheProperties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.userAccountMapper = userAccountMapper;
        this.auctionCacheProperties = auctionCacheProperties;
    }

    @Override
    public void prewarmAccountIfNeeded(String account) {
        if (account == null || account.isBlank() || hasWalletKey(account.trim())) {
            return;
        }

        UserAccount userAccount = userAccountMapper.findByAccount(account.trim());
        if (userAccount == null) {
            return;
        }

        initializeWalletIfNeeded(userAccount);
        writeWallet(userAccount);
    }

    @Override
    public void prewarmAccountsIfNeeded(List<String> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return;
        }

        for (String account : uniqueAccounts(accounts)) {
            prewarmAccountIfNeeded(account);
        }
    }

    @Override
    public UserAccount overlayWallet(UserAccount userAccount) {
        if (userAccount == null || userAccount.getAccount() == null || userAccount.getAccount().isBlank()) {
            return userAccount;
        }

        Map<Object, Object> cachedWallet = stringRedisTemplate.opsForHash().entries(walletKey(userAccount.getAccount()));
        if (cachedWallet == null || cachedWallet.isEmpty()) {
            return userAccount;
        }

        userAccount.setBalance(new BigDecimal(readValue(cachedWallet, "balance", "0.00")));
        userAccount.setFrozenAmount(new BigDecimal(readValue(cachedWallet, "frozenAmount", "0.00")));
        String updatedAtEpochMilli = readValue(cachedWallet, "updatedAtEpochMilli", "");
        if (!updatedAtEpochMilli.isBlank()) {
            userAccount.setUpdatedAt(Instant.ofEpochMilli(Long.parseLong(updatedAtEpochMilli)));
        }
        return userAccount;
    }

    @Override
    public void syncAuthoritativeWallet(UserAccount userAccount) {
        if (userAccount == null || userAccount.getAccount() == null || userAccount.getAccount().isBlank()) {
            return;
        }
        if (!hasWalletKey(userAccount.getAccount())) {
            return;
        }

        initializeWalletIfNeeded(userAccount);
        writeWallet(userAccount);
    }

    @Override
    @Transactional
    public void syncAccountsToMySql(List<String> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return;
        }

        for (String account : uniqueAccounts(accounts)) {
            if (!hasWalletKey(account)) {
                continue;
            }

            UserAccount userAccount = userAccountMapper.findByAccountForUpdate(account);
            if (userAccount == null) {
                continue;
            }

            overlayWallet(userAccount);
            userAccount.setUpdatedAt(Instant.now());
            userAccountMapper.updateWallet(userAccount);
            writeWallet(userAccount);
        }
    }

    @Override
    public String walletKeyPrefix() {
        return WALLET_KEY_PREFIX;
    }

    @Override
    public long walletTtlSeconds() {
        return Math.max(1L, auctionCacheProperties.getWalletTtl().toSeconds());
    }

    private boolean hasWalletKey(String account) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(walletKey(account)));
    }

    private void writeWallet(UserAccount userAccount) {
        Map<String, String> values = Map.of(
                "balance", userAccount.getBalance().toPlainString(),
                "frozenAmount", userAccount.getFrozenAmount().toPlainString(),
                "updatedAtEpochMilli", Long.toString(userAccount.getUpdatedAt().toEpochMilli())
        );
        stringRedisTemplate.opsForHash().putAll(walletKey(userAccount.getAccount()), values);
        stringRedisTemplate.expire(walletKey(userAccount.getAccount()), auctionCacheProperties.getWalletTtl());
    }

    private String walletKey(String account) {
        return WALLET_KEY_PREFIX + account;
    }

    private String readValue(Map<Object, Object> values, String field, String defaultValue) {
        Object value = values.get(field);
        return value == null || value.toString().isBlank() ? defaultValue : value.toString();
    }

    private void initializeWalletIfNeeded(UserAccount userAccount) {
        if (userAccount.getBalance() == null) {
            userAccount.setBalance(BigDecimal.ZERO);
        }
        if (userAccount.getFrozenAmount() == null) {
            userAccount.setFrozenAmount(BigDecimal.ZERO);
        }
        if (userAccount.getUpdatedAt() == null) {
            userAccount.setUpdatedAt(Instant.now());
        }
    }

    private Set<String> uniqueAccounts(List<String> accounts) {
        Set<String> unique = new LinkedHashSet<>();
        for (String account : accounts) {
            if (account != null && !account.isBlank()) {
                unique.add(account.trim());
            }
        }
        return unique;
    }
}

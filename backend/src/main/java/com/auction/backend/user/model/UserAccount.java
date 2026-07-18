package com.auction.backend.user.model;

import java.math.BigDecimal;
import java.time.Instant;

public class UserAccount {

    private String userId;
    private String account;
    private String password;
    private String nickname;
    private String avatarUrl;
    private String bio;
    private BigDecimal balance;
    private BigDecimal frozenAmount;
    private Instant createdAt;
    private Instant updatedAt;

    public UserAccount() {
    }

    public UserAccount(String userId,
                       String account,
                       String password,
                       String nickname,
                       String avatarUrl,
                       String bio,
                       BigDecimal balance,
                       BigDecimal frozenAmount,
                       Instant createdAt,
                       Instant updatedAt) {
        this.userId = userId;
        this.account = account;
        this.password = password;
        this.nickname = nickname;
        this.avatarUrl = avatarUrl;
        this.bio = bio;
        this.balance = balance;
        this.frozenAmount = frozenAmount;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public BigDecimal getFrozenAmount() {
        return frozenAmount;
    }

    public void setFrozenAmount(BigDecimal frozenAmount) {
        this.frozenAmount = frozenAmount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

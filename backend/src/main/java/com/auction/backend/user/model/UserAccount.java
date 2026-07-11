package com.auction.backend.user.model;

import java.time.Instant;

public class UserAccount {

    private String userId;
    private String account;
    private String password;
    private String nickname;
    private String avatarUrl;
    private String bio;
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
                       Instant createdAt,
                       Instant updatedAt) {
        this.userId = userId;
        this.account = account;
        this.password = password;
        this.nickname = nickname;
        this.avatarUrl = avatarUrl;
        this.bio = bio;
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

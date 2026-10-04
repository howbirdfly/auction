package com.auction.backend.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

public class AuctionPrincipal implements UserDetails {

    private final String userId;
    private final String account;
    private final String nickname;
    private final String password;
    private final String role;

    public AuctionPrincipal(String userId,
                            String account,
                            String nickname,
                            String password,
                            String role) {
        this.userId = userId;
        this.account = account;
        this.nickname = nickname;
        this.password = password;
        this.role = role;
    }

    public String getUserId() {
        return userId;
    }

    public String getAccount() {
        return account;
    }

    public String getNickname() {
        return nickname;
    }

    public String getRole() {
        return role;
    }

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return account;
    }
}

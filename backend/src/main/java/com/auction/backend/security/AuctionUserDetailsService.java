package com.auction.backend.security;

import com.auction.backend.user.mapper.UserAccountMapper;
import com.auction.backend.user.model.UserAccount;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AuctionUserDetailsService implements UserDetailsService {

    private final UserAccountMapper userAccountMapper;

    public AuctionUserDetailsService(UserAccountMapper userAccountMapper) {
        this.userAccountMapper = userAccountMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserAccount userAccount = userAccountMapper.findByAccount(username.trim());
        if (userAccount == null) {
            throw new UsernameNotFoundException("account or password is incorrect");
        }

        String role = "ADMIN".equalsIgnoreCase(userAccount.getRole()) ? "ADMIN" : "USER";
        return new AuctionPrincipal(
                userAccount.getUserId(),
                userAccount.getAccount(),
                userAccount.getNickname(),
                userAccount.getPassword(),
                role
        );
    }
}

package com.auction.backend.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthorizationService {

    public AuctionPrincipal requirePrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuctionPrincipal principal)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
        }
        return principal;
    }

    public void requireSelfOrAdmin(String userId) {
        AuctionPrincipal principal = requirePrincipal();
        if (!principal.isAdmin() && !principal.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "cannot access another user's data");
        }
    }

    public void requireAccount(String account) {
        AuctionPrincipal principal = requirePrincipal();
        if (!principal.getAccount().equals(account)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "request user does not match the authenticated account");
        }
    }
}

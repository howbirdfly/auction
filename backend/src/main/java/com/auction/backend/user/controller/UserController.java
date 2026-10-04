package com.auction.backend.user.controller;

import com.auction.backend.common.ApiResponse;
import com.auction.backend.security.AuctionPrincipal;
import com.auction.backend.security.AuthorizationService;
import com.auction.backend.user.dto.CreateUserRequest;
import com.auction.backend.user.dto.UpdateUserRequest;
import com.auction.backend.user.dto.UserAuctionHistorySnapshot;
import com.auction.backend.user.dto.UserLoginRequest;
import com.auction.backend.user.dto.UserProfileSnapshot;
import com.auction.backend.user.dto.UserRechargeRequest;
import com.auction.backend.user.dto.WalletTransactionSnapshot;
import com.auction.backend.user.service.UserAuctionHistoryService;
import com.auction.backend.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final UserAuctionHistoryService userAuctionHistoryService;
    private final AuthenticationManager authenticationManager;
    private final AuthorizationService authorizationService;
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    public UserController(UserService userService,
                          UserAuctionHistoryService userAuctionHistoryService,
                          AuthenticationManager authenticationManager,
                          AuthorizationService authorizationService) {
        this.userService = userService;
        this.userAuctionHistoryService = userAuctionHistoryService;
        this.authenticationManager = authenticationManager;
        this.authorizationService = authorizationService;
    }

    @GetMapping
    public ApiResponse<List<UserProfileSnapshot>> listUsers() {
        return ApiResponse.success(userService.listUsers());
    }

    @GetMapping("/{userId}")
    public ApiResponse<UserProfileSnapshot> getUser(@PathVariable String userId) {
        authorizationService.requireSelfOrAdmin(userId);
        return ApiResponse.success(userService.getUser(userId));
    }

    @GetMapping("/me")
    public ApiResponse<UserProfileSnapshot> getCurrentUser() {
        AuctionPrincipal principal = authorizationService.requirePrincipal();
        return ApiResponse.success(userService.getUser(principal.getUserId()));
    }

    @GetMapping("/{userId}/auction-history")
    public ApiResponse<UserAuctionHistorySnapshot> getAuctionHistory(@PathVariable String userId) {
        authorizationService.requireSelfOrAdmin(userId);
        return ApiResponse.success(userAuctionHistoryService.getHistory(userId));
    }

    @GetMapping("/{userId}/wallet-transactions")
    public ApiResponse<List<WalletTransactionSnapshot>> getWalletTransactions(@PathVariable String userId) {
        authorizationService.requireSelfOrAdmin(userId);
        return ApiResponse.success(userService.listWalletTransactions(userId));
    }

    @PostMapping("/register")
    public ApiResponse<UserProfileSnapshot> register(@Valid @RequestBody CreateUserRequest request,
                                                     HttpServletRequest httpRequest,
                                                     HttpServletResponse httpResponse) {
        UserProfileSnapshot user = userService.register(request);
        persistAuthentication(request.account(), request.password(), httpRequest, httpResponse);
        return ApiResponse.success("user created", user);
    }

    @PostMapping("/login")
    public ApiResponse<UserProfileSnapshot> login(@Valid @RequestBody UserLoginRequest request,
                                                  HttpServletRequest httpRequest,
                                                  HttpServletResponse httpResponse) {
        AuctionPrincipal principal = persistAuthentication(
                request.account(),
                request.password(),
                httpRequest,
                httpResponse
        );
        return ApiResponse.success("login success", userService.getUser(principal.getUserId()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        if (request.getSession(false) != null) {
            request.getSession(false).invalidate();
        }
        SecurityContextHolder.clearContext();
        return ApiResponse.success("logout success", null);
    }

    @PutMapping("/{userId}")
    public ApiResponse<UserProfileSnapshot> updateUser(@PathVariable String userId,
                                                       @Valid @RequestBody UpdateUserRequest request) {
        authorizationService.requireSelfOrAdmin(userId);
        return ApiResponse.success("user updated", userService.updateUser(userId, request));
    }

    @PostMapping("/{userId}/recharge")
    public ApiResponse<UserProfileSnapshot> recharge(@PathVariable String userId,
                                                     @Valid @RequestBody UserRechargeRequest request) {
        authorizationService.requireSelfOrAdmin(userId);
        return ApiResponse.success("balance recharged", userService.recharge(userId, request));
    }

    private AuctionPrincipal persistAuthentication(String account,
                                                    String password,
                                                    HttpServletRequest request,
                                                    HttpServletResponse response) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(account.trim(), password)
            );
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            if (request.getSession(false) != null) {
                request.changeSessionId();
            } else {
                request.getSession(true);
            }
            securityContextRepository.saveContext(context, request, response);
            return (AuctionPrincipal) authentication.getPrincipal();
        } catch (AuthenticationException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "account or password is incorrect");
        }
    }
}

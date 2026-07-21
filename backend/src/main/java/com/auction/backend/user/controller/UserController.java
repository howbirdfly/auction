package com.auction.backend.user.controller;

import com.auction.backend.common.ApiResponse;
import com.auction.backend.user.dto.CreateUserRequest;
import com.auction.backend.user.dto.UpdateUserRequest;
import com.auction.backend.user.dto.UserAuctionHistorySnapshot;
import com.auction.backend.user.dto.UserLoginRequest;
import com.auction.backend.user.dto.UserProfileSnapshot;
import com.auction.backend.user.dto.UserRechargeRequest;
import com.auction.backend.user.dto.WalletTransactionSnapshot;
import com.auction.backend.user.service.UserAuctionHistoryService;
import com.auction.backend.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final UserAuctionHistoryService userAuctionHistoryService;

    public UserController(UserService userService,
                          UserAuctionHistoryService userAuctionHistoryService) {
        this.userService = userService;
        this.userAuctionHistoryService = userAuctionHistoryService;
    }

    @GetMapping
    public ApiResponse<List<UserProfileSnapshot>> listUsers() {
        return ApiResponse.success(userService.listUsers());
    }

    @GetMapping("/{userId}")
    public ApiResponse<UserProfileSnapshot> getUser(@PathVariable String userId) {
        return ApiResponse.success(userService.getUser(userId));
    }

    @GetMapping("/{userId}/auction-history")
    public ApiResponse<UserAuctionHistorySnapshot> getAuctionHistory(@PathVariable String userId) {
        return ApiResponse.success(userAuctionHistoryService.getHistory(userId));
    }

    @GetMapping("/{userId}/wallet-transactions")
    public ApiResponse<List<WalletTransactionSnapshot>> getWalletTransactions(@PathVariable String userId) {
        return ApiResponse.success(userService.listWalletTransactions(userId));
    }

    @PostMapping("/register")
    public ApiResponse<UserProfileSnapshot> register(@Valid @RequestBody CreateUserRequest request) {
        return ApiResponse.success("user created", userService.register(request));
    }

    @PostMapping("/login")
    public ApiResponse<UserProfileSnapshot> login(@Valid @RequestBody UserLoginRequest request) {
        return ApiResponse.success("login success", userService.login(request));
    }

    @PutMapping("/{userId}")
    public ApiResponse<UserProfileSnapshot> updateUser(@PathVariable String userId,
                                                       @Valid @RequestBody UpdateUserRequest request) {
        return ApiResponse.success("user updated", userService.updateUser(userId, request));
    }

    @PostMapping("/{userId}/recharge")
    public ApiResponse<UserProfileSnapshot> recharge(@PathVariable String userId,
                                                     @Valid @RequestBody UserRechargeRequest request) {
        return ApiResponse.success("balance recharged", userService.recharge(userId, request));
    }
}

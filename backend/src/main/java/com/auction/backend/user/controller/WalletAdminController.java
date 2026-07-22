package com.auction.backend.user.controller;

import com.auction.backend.common.ApiResponse;
import com.auction.backend.user.dto.WalletReconcileIssueSnapshot;
import com.auction.backend.user.dto.WalletReconcileResult;
import com.auction.backend.user.service.WalletReconcileService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/wallets")
public class WalletAdminController {

    private final WalletReconcileService walletReconcileService;

    public WalletAdminController(WalletReconcileService walletReconcileService) {
        this.walletReconcileService = walletReconcileService;
    }

    @PostMapping("/reconcile")
    public ApiResponse<List<WalletReconcileResult>> reconcileAll() {
        return ApiResponse.success("wallet reconcile completed", walletReconcileService.reconcileAll());
    }

    @PostMapping("/{userId}/reconcile")
    public ApiResponse<WalletReconcileResult> reconcileUser(@PathVariable String userId) {
        return ApiResponse.success("wallet reconcile completed", walletReconcileService.reconcileUser(userId));
    }

    @GetMapping("/reconcile-issues")
    public ApiResponse<List<WalletReconcileIssueSnapshot>> listIssues(
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(walletReconcileService.listIssues(limit));
    }

    @GetMapping("/{userId}/reconcile-issues")
    public ApiResponse<List<WalletReconcileIssueSnapshot>> listUserIssues(@PathVariable String userId,
                                                                          @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(walletReconcileService.listUserIssues(userId, limit));
    }
}

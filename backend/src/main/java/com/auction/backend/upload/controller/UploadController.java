package com.auction.backend.upload.controller;

import com.auction.backend.common.ApiResponse;
import com.auction.backend.security.AuthorizationService;
import com.auction.backend.upload.dto.AvatarUploadPolicyRequest;
import com.auction.backend.upload.dto.AvatarUploadPolicySnapshot;
import com.auction.backend.upload.dto.RoomCoverUploadPolicyRequest;
import com.auction.backend.upload.service.OssUploadService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/uploads")
public class UploadController {

    private final OssUploadService ossUploadService;
    private final AuthorizationService authorizationService;

    public UploadController(OssUploadService ossUploadService,
                            AuthorizationService authorizationService) {
        this.ossUploadService = ossUploadService;
        this.authorizationService = authorizationService;
    }

    @PostMapping("/avatar-policy")
    public ApiResponse<AvatarUploadPolicySnapshot> createAvatarUploadPolicy(
            @Valid @RequestBody AvatarUploadPolicyRequest request
    ) {
        authorizationService.requireSelfOrAdmin(request.userId());
        return ApiResponse.success("avatar upload policy created", ossUploadService.createAvatarUploadPolicy(request));
    }

    @PostMapping("/room-cover-policy")
    public ApiResponse<AvatarUploadPolicySnapshot> createRoomCoverUploadPolicy(
            @Valid @RequestBody RoomCoverUploadPolicyRequest request
    ) {
        authorizationService.requireSelfOrAdmin(request.userId());
        return ApiResponse.success("room cover upload policy created", ossUploadService.createRoomCoverUploadPolicy(request));
    }
}

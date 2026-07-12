package com.auction.backend.upload.controller;

import com.auction.backend.common.ApiResponse;
import com.auction.backend.upload.dto.AvatarUploadPolicyRequest;
import com.auction.backend.upload.dto.AvatarUploadPolicySnapshot;
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

    public UploadController(OssUploadService ossUploadService) {
        this.ossUploadService = ossUploadService;
    }

    @PostMapping("/avatar-policy")
    public ApiResponse<AvatarUploadPolicySnapshot> createAvatarUploadPolicy(
            @Valid @RequestBody AvatarUploadPolicyRequest request
    ) {
        return ApiResponse.success("avatar upload policy created", ossUploadService.createAvatarUploadPolicy(request));
    }
}

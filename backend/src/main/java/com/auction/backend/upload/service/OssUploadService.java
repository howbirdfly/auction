package com.auction.backend.upload.service;

import com.auction.backend.upload.config.OssProperties;
import com.auction.backend.upload.dto.AvatarUploadPolicyRequest;
import com.auction.backend.upload.dto.AvatarUploadPolicySnapshot;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OssUploadService {

    private static final List<String> ALLOWED_CONTENT_TYPES = List.of(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/gif"
    );
    private static final int SUCCESS_ACTION_STATUS = 200;
    private static final long MAX_AVATAR_SIZE_BYTES = 5L * 1024L * 1024L;

    private final OssProperties ossProperties;

    public OssUploadService(OssProperties ossProperties) {
        this.ossProperties = ossProperties;
    }

    public AvatarUploadPolicySnapshot createAvatarUploadPolicy(AvatarUploadPolicyRequest request) {
        validateOssConfigured();
        validateContentType(request.contentType());

        Instant expireAt = Instant.now().plus(5, ChronoUnit.MINUTES);
        String extension = resolveExtension(request.fileName(), request.contentType());
        String objectKey = "avatars/" + sanitizeSegment(request.userId()) + "/"
                + Instant.now().toEpochMilli() + "-" + UUID.randomUUID().toString().replace("-", "")
                + extension;

        String policyJson = buildPolicyJson(expireAt, objectKey);
        String encodedPolicy = Base64.getEncoder().encodeToString(policyJson.getBytes(StandardCharsets.UTF_8));
        String signature = signPolicy(encodedPolicy, ossProperties.getAccessKeySecret());
        String host = "https://" + ossProperties.getBucketName() + "." + normalizeEndpoint(ossProperties.getEndpoint());
        String publicUrl = host + "/" + objectKey;

        return new AvatarUploadPolicySnapshot(
                host,
                objectKey,
                encodedPolicy,
                ossProperties.getAccessKeyId(),
                signature,
                SUCCESS_ACTION_STATUS,
                publicUrl,
                expireAt
        );
    }

    private void validateOssConfigured() {
        if (isBlank(ossProperties.getEndpoint())
                || isBlank(ossProperties.getBucketName())
                || isBlank(ossProperties.getAccessKeyId())
                || isBlank(ossProperties.getAccessKeySecret())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "oss upload is not configured");
        }
    }

    private void validateContentType(String contentType) {
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "only jpg, png, webp and gif avatars are supported");
        }
    }

    private String buildPolicyJson(Instant expireAt, String objectKey) {
        String prefix = objectKey.substring(0, objectKey.lastIndexOf('/') + 1);
        return "{\"expiration\":\"" + expireAt.toString() + "\",\"conditions\":["
                + "{\"bucket\":\"" + escapeJson(ossProperties.getBucketName()) + "\"},"
                + "[\"starts-with\",\"$key\",\"" + escapeJson(prefix) + "\"],"
                + "[\"eq\",\"$success_action_status\",\"" + SUCCESS_ACTION_STATUS + "\"],"
                + "[\"content-length-range\",0," + MAX_AVATAR_SIZE_BYTES + "]"
                + "]}";
    }

    private String signPolicy(String encodedPolicy, String accessKeySecret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(accessKeySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(encodedPolicy.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "failed to sign oss policy");
        }
    }

    private String resolveExtension(String fileName, String contentType) {
        String lowerCaseName = fileName == null ? "" : fileName.toLowerCase();
        if (lowerCaseName.endsWith(".jpg") || lowerCaseName.endsWith(".jpeg")) {
            return ".jpg";
        }
        if (lowerCaseName.endsWith(".png")) {
            return ".png";
        }
        if (lowerCaseName.endsWith(".webp")) {
            return ".webp";
        }
        if (lowerCaseName.endsWith(".gif")) {
            return ".gif";
        }

        return switch (contentType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".jpg";
        };
    }

    private String sanitizeSegment(String value) {
        return value.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private String normalizeEndpoint(String endpoint) {
        return endpoint
                .replace("https://", "")
                .replace("http://", "")
                .replaceAll("/+$", "");
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

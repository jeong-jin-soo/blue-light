package com.bluelight.backend.api.user;

import com.bluelight.backend.api.user.dto.ChangePasswordRequest;
import com.bluelight.backend.api.user.dto.UpdateProfileRequest;
import com.bluelight.backend.api.user.dto.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * User profile API controller
 */
@Slf4j
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * Get my profile
     * GET /api/users/me
     */
    @GetMapping("/me")
    public ResponseEntity<UserResponse> getMyProfile(Authentication authentication) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Get profile: userSeq={}", userSeq);
        UserResponse response = userService.getProfile(userSeq);
        return ResponseEntity.ok(response);
    }

    /**
     * Update my profile
     * PUT /api/users/me
     */
    @PutMapping("/me")
    public ResponseEntity<UserResponse> updateMyProfile(
            Authentication authentication,
            @Valid @RequestBody UpdateProfileRequest request) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Update profile: userSeq={}", userSeq);
        UserResponse response = userService.updateProfile(userSeq, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Change password
     * PUT /api/users/me/password
     */
    @PutMapping("/me/password")
    public ResponseEntity<Void> changePassword(
            Authentication authentication,
            @Valid @RequestBody ChangePasswordRequest request) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Change password: userSeq={}", userSeq);
        userService.changePassword(userSeq, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Upload or replace profile signature
     * PUT /api/users/me/signature
     */
    @PutMapping("/me/signature")
    public ResponseEntity<UserResponse> uploadSignature(
            Authentication authentication,
            @RequestParam("signature") MultipartFile signatureImage) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Upload signature: userSeq={}", userSeq);
        UserResponse response = userService.uploadSignature(userSeq, signatureImage);
        return ResponseEntity.ok(response);
    }

    /**
     * Delete profile signature
     * DELETE /api/users/me/signature
     */
    @DeleteMapping("/me/signature")
    public ResponseEntity<Void> deleteSignature(Authentication authentication) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Delete signature: userSeq={}", userSeq);
        userService.deleteSignature(userSeq);
        return ResponseEntity.noContent().build();
    }

    /**
     * Get profile signature image
     * GET /api/users/me/signature
     */
    @GetMapping("/me/signature")
    public ResponseEntity<Resource> getSignature(Authentication authentication) {
        Long userSeq = (Long) authentication.getPrincipal();
        Resource resource = userService.getSignatureResource(userSeq);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(resource);
    }

    /**
     * PDPA: Withdraw PDPA consent (Right to Withdrawal)
     * POST /api/users/me/withdraw-consent
     * - PDPA 동의 철회 → 계정은 유지하되, 동의 기반 서비스(챗봇 등) 제한
     */
    @PostMapping("/me/withdraw-consent")
    public ResponseEntity<Map<String, String>> withdrawPdpaConsent(Authentication authentication) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("PDPA consent withdrawal requested: userSeq={}", userSeq);
        userService.withdrawPdpaConsent(userSeq);
        return ResponseEntity.ok(Map.of(
                "message", "PDPA consent has been withdrawn. Some services may be restricted."
        ));
    }

    /**
     * PDPA: Export my personal data (Right to Access / Data Portability)
     * GET /api/users/me/data-export
     */
    @GetMapping("/me/data-export")
    public ResponseEntity<Map<String, Object>> exportMyData(Authentication authentication) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Data export requested: userSeq={}", userSeq);
        Map<String, Object> data = userService.exportUserData(userSeq);
        return ResponseEntity.ok(data);
    }

    /**
     * PDPA: Delete my account (Right to Erasure)
     * DELETE /api/users/me
     * - 개인정보 익명화 + soft delete
     * - 법적 보존 의무가 있는 신청 기록은 유지
     */
    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteMyAccount(Authentication authentication) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Account deletion requested: userSeq={}", userSeq);
        userService.deleteAccount(userSeq);
        return ResponseEntity.noContent().build();
    }
}

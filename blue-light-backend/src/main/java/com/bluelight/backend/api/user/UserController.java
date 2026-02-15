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
}

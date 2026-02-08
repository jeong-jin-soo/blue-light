package com.bluelight.backend.api.user;

import com.bluelight.backend.api.user.dto.ChangePasswordRequest;
import com.bluelight.backend.api.user.dto.UpdateProfileRequest;
import com.bluelight.backend.api.user.dto.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

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
}

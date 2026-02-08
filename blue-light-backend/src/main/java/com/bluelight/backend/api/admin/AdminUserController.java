package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.AdminUserResponse;
import com.bluelight.backend.api.admin.dto.ChangeRoleRequest;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserRole;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin User Management API controller (ADMIN role only)
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final UserRepository userRepository;

    /**
     * Get all users
     * GET /api/admin/users
     */
    @GetMapping
    public ResponseEntity<List<AdminUserResponse>> getAllUsers() {
        log.info("Admin get all users");
        List<AdminUserResponse> users = userRepository.findAll()
                .stream()
                .map(AdminUserResponse::from)
                .toList();
        return ResponseEntity.ok(users);
    }

    /**
     * Change user role (APPLICANT <-> LEW only)
     * PATCH /api/admin/users/:id/role
     */
    @PatchMapping("/{id}/role")
    @Transactional
    public ResponseEntity<AdminUserResponse> changeRole(
            @PathVariable Long id,
            @Valid @RequestBody ChangeRoleRequest request) {

        User user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(
                        "User not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        // ADMIN 사용자의 역할은 변경 불가
        if (user.getRole() == UserRole.ADMIN) {
            throw new BusinessException(
                    "Cannot change role of an admin user",
                    HttpStatus.BAD_REQUEST, "CANNOT_CHANGE_ADMIN_ROLE");
        }

        // ADMIN 역할로 변경 불가
        UserRole targetRole;
        try {
            targetRole = UserRole.valueOf(request.getRole().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(
                    "Invalid role: " + request.getRole(),
                    HttpStatus.BAD_REQUEST, "INVALID_ROLE");
        }

        if (targetRole == UserRole.ADMIN) {
            throw new BusinessException(
                    "Cannot assign ADMIN role through this endpoint",
                    HttpStatus.BAD_REQUEST, "CANNOT_ASSIGN_ADMIN");
        }

        // changeRole이 approvedStatus도 자동 연동 (LEW → PENDING, APPLICANT → null)
        user.changeRole(targetRole);
        log.info("User role changed: userSeq={}, newRole={}", id, targetRole);

        return ResponseEntity.ok(AdminUserResponse.from(user));
    }

    /**
     * Approve LEW user
     * POST /api/admin/users/:id/approve
     */
    @PostMapping("/{id}/approve")
    @Transactional
    public ResponseEntity<AdminUserResponse> approveLew(@PathVariable Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(
                        "User not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        if (user.getRole() != UserRole.LEW) {
            throw new BusinessException(
                    "Only LEW users can be approved",
                    HttpStatus.BAD_REQUEST, "NOT_LEW_USER");
        }

        user.approve();
        log.info("LEW approved: userSeq={}, email={}", id, user.getEmail());

        return ResponseEntity.ok(AdminUserResponse.from(user));
    }

    /**
     * Reject LEW user
     * POST /api/admin/users/:id/reject
     */
    @PostMapping("/{id}/reject")
    @Transactional
    public ResponseEntity<AdminUserResponse> rejectLew(@PathVariable Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(
                        "User not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        if (user.getRole() != UserRole.LEW) {
            throw new BusinessException(
                    "Only LEW users can be rejected",
                    HttpStatus.BAD_REQUEST, "NOT_LEW_USER");
        }

        user.reject();
        log.info("LEW rejected: userSeq={}, email={}", id, user.getEmail());

        return ResponseEntity.ok(AdminUserResponse.from(user));
    }
}

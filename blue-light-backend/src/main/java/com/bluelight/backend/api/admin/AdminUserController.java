package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.AdminUserResponse;
import com.bluelight.backend.api.admin.dto.ChangeRoleRequest;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.audit.Auditable;
import com.bluelight.backend.common.util.EnumParser;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserRole;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Admin User Management API controller (ADMIN + SYSTEM_ADMIN)
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM_ADMIN')")
public class AdminUserController {

    private final UserRepository userRepository;

    /**
     * Get all users (paginated, optional role filter and search)
     * GET /api/admin/users?page=0&size=20&role=LEW&search=keyword
     */
    @GetMapping
    public ResponseEntity<Page<AdminUserResponse>> getAllUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String search) {
        log.info("Admin get users: page={}, size={}, role={}, search={}", page, size, role, search);

        // 페이지네이션 파라미터 검증
        if (page < 0) page = 0;
        if (size < 1 || size > 100) size = 20;

        Pageable pageable = PageRequest.of(page, size);

        // 역할 파싱
        UserRole roleFilter = EnumParser.parseNullable(UserRole.class, role, "INVALID_ROLE");

        boolean hasSearch = search != null && !search.trim().isEmpty();
        Page<User> userPage;

        if (hasSearch && roleFilter != null) {
            userPage = userRepository.searchByKeywordAndRole(search.trim(), roleFilter, pageable);
        } else if (hasSearch) {
            userPage = userRepository.searchByKeyword(search.trim(), pageable);
        } else if (roleFilter != null) {
            userPage = userRepository.findByRoleOrderByCreatedAtDesc(roleFilter, pageable);
        } else {
            userPage = userRepository.findAllByOrderByCreatedAtDesc(pageable);
        }

        Page<AdminUserResponse> responsePage = userPage.map(AdminUserResponse::from);
        return ResponseEntity.ok(responsePage);
    }

    /**
     * Change user role (APPLICANT <-> LEW only)
     * PATCH /api/admin/users/:id/role
     */
    @Auditable(action = AuditAction.USER_ROLE_CHANGED, category = AuditCategory.ADMIN, entityType = "User")
    @PatchMapping("/{id}/role")
    @Transactional
    public ResponseEntity<AdminUserResponse> changeRole(
            @PathVariable Long id,
            @Valid @RequestBody ChangeRoleRequest request) {

        User user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(
                        "User not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        // ADMIN / SYSTEM_ADMIN 사용자의 역할은 변경 불가
        if (user.getRole() == UserRole.ADMIN || user.getRole() == UserRole.SYSTEM_ADMIN) {
            throw new BusinessException(
                    "Cannot change role of an admin user",
                    HttpStatus.BAD_REQUEST, "CANNOT_CHANGE_ADMIN_ROLE");
        }

        // ADMIN / SYSTEM_ADMIN 역할로 변경 불가
        UserRole targetRole = EnumParser.parse(UserRole.class, request.getRole(), "INVALID_ROLE");

        if (targetRole == UserRole.ADMIN || targetRole == UserRole.SYSTEM_ADMIN) {
            throw new BusinessException(
                    "Cannot assign ADMIN or SYSTEM_ADMIN role through this endpoint",
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
    @Auditable(action = AuditAction.LEW_APPROVED, category = AuditCategory.ADMIN, entityType = "User")
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
    @Auditable(action = AuditAction.LEW_REJECTED, category = AuditCategory.ADMIN, entityType = "User")
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

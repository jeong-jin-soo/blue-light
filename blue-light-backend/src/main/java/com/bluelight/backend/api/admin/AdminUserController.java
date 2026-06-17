package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.AdminUserResponse;
import com.bluelight.backend.api.admin.dto.ChangeRoleRequest;
import com.bluelight.backend.api.admin.dto.InviteLewRequest;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.audit.Auditable;
import com.bluelight.backend.common.util.EnumParser;
import com.bluelight.backend.domain.user.ApprovalStatus;
import com.bluelight.backend.domain.user.LewGrade;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserRole;
import jakarta.servlet.http.HttpServletRequest;
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
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final AdminLewInviteService lewInviteService;

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

        if (targetRole == UserRole.LEW) {
            // LEW 승격은 면허번호·등급을 함께 등록해야 한다 (등급 null LEW 가 배정 단계에서 막히는 무결성 구멍 방지).
            if (request.getLewLicenceNo() == null || request.getLewLicenceNo().isBlank()) {
                throw new BusinessException(
                        "LEW licence number is required", HttpStatus.BAD_REQUEST, "LEW_LICENCE_NO_REQUIRED");
            }
            if (request.getLewGrade() == null || request.getLewGrade().isBlank()) {
                throw new BusinessException(
                        "LEW grade is required", HttpStatus.BAD_REQUEST, "LEW_GRADE_REQUIRED");
            }
            // 면허번호 정규화(trim) + 중복 검사 — 본인 제외 (한 실물 LEW = 한 계정)
            String licenceNo = request.getLewLicenceNo().trim();
            if (userRepository.existsByLewLicenceNoAndUserSeqNot(licenceNo, id)) {
                throw new BusinessException(
                        "LEW licence number is already registered",
                        HttpStatus.CONFLICT, "DUPLICATE_LEW_LICENCE_NO");
            }
            LewGrade grade = EnumParser.parse(LewGrade.class, request.getLewGrade(), "INVALID_LEW_GRADE");
            user.changeRoleToLew(licenceNo, grade);
        } else {
            // approvedStatus·LEW 자격은 changeRole 이 자동 정리 (→ null)
            user.changeRole(targetRole);
        }
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
        // #7 상태 머신 가드: 이미 APPROVED면 거부 (중복 승인 → 중복 알림/감사 방지).
        if (user.getApprovedStatus() == ApprovalStatus.APPROVED) {
            throw new BusinessException(
                    "LEW is already approved", HttpStatus.CONFLICT, "LEW_ALREADY_APPROVED");
        }

        user.approve();
        log.info("LEW approved: userSeq={}, email={}", id, user.getEmail());

        // 본인에게 인앱+이메일 통지 (AFTER_COMMIT 리스너)
        eventPublisher.publishEvent(new LewApprovalDecisionEvent(user.getUserSeq(), true));

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
        // #7 상태 머신 가드: PENDING에서만 거절. 이미 REJECTED(중복) 또는 APPROVED(권한 회수는 별도)는 거부.
        if (user.getApprovedStatus() == ApprovalStatus.APPROVED) {
            throw new BusinessException(
                    "Cannot reject an approved LEW (revocation is a separate action)",
                    HttpStatus.CONFLICT, "LEW_CANNOT_REJECT_APPROVED");
        }
        if (user.getApprovedStatus() == ApprovalStatus.REJECTED) {
            throw new BusinessException(
                    "LEW is already rejected", HttpStatus.CONFLICT, "LEW_ALREADY_REJECTED");
        }

        user.reject();
        log.info("LEW rejected: userSeq={}, email={}", id, user.getEmail());

        // 본인에게 인앱+이메일 통지 (AFTER_COMMIT 리스너)
        eventPublisher.publishEvent(new LewApprovalDecisionEvent(user.getUserSeq(), false));

        return ResponseEntity.ok(AdminUserResponse.from(user));
    }

    /**
     * Invite a LEW by email (creates a PENDING_ACTIVATION LEW account + setup token + invitation email)
     * POST /api/admin/users/invite-lew
     */
    @Auditable(action = AuditAction.LEW_INVITATION_SENT, category = AuditCategory.ADMIN, entityType = "User")
    @PostMapping("/invite-lew")
    public ResponseEntity<AdminUserResponse> inviteLew(
            @Valid @RequestBody InviteLewRequest request,
            HttpServletRequest http) {
        AdminUserResponse response = lewInviteService.invite(request, http);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Resend a LEW invitation (PENDING_ACTIVATION invited LEW only; revokes prior active token)
     * POST /api/admin/users/:id/resend-invite
     */
    @Auditable(action = AuditAction.ACCOUNT_SETUP_TOKEN_ISSUED, category = AuditCategory.ADMIN, entityType = "User")
    @PostMapping("/{id}/resend-invite")
    public ResponseEntity<AdminUserResponse> resendInvite(
            @PathVariable Long id,
            HttpServletRequest http) {
        AdminUserResponse response = lewInviteService.resendInvite(id, http);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
}

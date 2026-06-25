package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.*;
import com.bluelight.backend.common.security.AuthPrincipal;
import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.domain.application.KvaStatus;
import com.bluelight.backend.domain.application.LicenseStatus;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.audit.Auditable;
import com.bluelight.backend.security.GenericRateLimiter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin/LEW 신청 관리 핵심 API 컨트롤러
 * - 대시보드, 신청 목록/상세, 상태 변경, 보완 요청, 승인, 완료, 결제
 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'LEW', 'SYSTEM_ADMIN')")
public class AdminApplicationController {

    private final AdminApplicationService adminApplicationService;
    private final AdminPaymentService adminPaymentService;
    /** ★ Concierge 강화 + 별도 수금 PR-2 — Application 별도 수금. */
    private final com.bluelight.backend.api.payment.ManualPaymentService manualPaymentService;
    private final com.bluelight.backend.api.audit.AuditLogService auditLogService;
    private final GenericRateLimiter rateLimiter;

    /** 결제 확인: 신청서당 5분 내 최대 3회 */
    private static final String RATE_TYPE_PAYMENT = "PAYMENT_CONFIRM";
    private static final int PAYMENT_MAX = 3;
    private static final long PAYMENT_WINDOW_MIN = 5;

    /**
     * Get admin dashboard summary
     * GET /api/admin/dashboard
     */
    @GetMapping("/dashboard")
    public ResponseEntity<AdminDashboardResponse> getDashboard(Authentication authentication) {
        Long userSeq = AuthPrincipal.userSeq(authentication);
        String role = AuthPrincipal.role(authentication);
        log.info("Admin dashboard requested: userSeq={}, role={}", userSeq, role);
        AdminDashboardResponse response = adminApplicationService.getDashboardSummary(userSeq, role);
        return ResponseEntity.ok(response);
    }

    /**
     * Get all applications (paginated, optional status filter)
     * GET /api/admin/applications?status=PENDING_PAYMENT&page=0&size=20
     * LEW는 자신에게 배정된 신청서만 조회
     */
    @GetMapping("/applications")
    public ResponseEntity<Page<AdminApplicationResponse>> getAllApplications(
            Authentication authentication,
            @RequestParam(required = false) ApplicationStatus status,
            @RequestParam(required = false) KvaStatus kvaStatus,
            @RequestParam(required = false) LicenseStatus licenseStatus,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userSeq = AuthPrincipal.userSeq(authentication);
        String role = AuthPrincipal.role(authentication);
        int validPage = Math.max(0, page);
        int validSize = Math.min(Math.max(1, size), 100);
        log.info("Admin get all applications: userSeq={}, role={}, status={}, kvaStatus={}, licenseStatus={}, search={}, page={}, size={}",
                userSeq, role, status, kvaStatus, licenseStatus, search, validPage, validSize);
        Pageable pageable = PageRequest.of(validPage, validSize);
        Page<AdminApplicationResponse> applications =
                adminApplicationService.getAllApplications(status, kvaStatus, licenseStatus, search, pageable, userSeq, role);
        return ResponseEntity.ok(applications);
    }

    /**
     * Get application detail (admin view)
     * GET /api/admin/applications/:id
     *
     * <p>★ 코드 부채 P0 (PR-T8/L-3 후속) — LEW cross-tenant 가드는 메서드 @PreAuthorize 의
     * @appSec.isAssignedLew SpEL 빈으로 단일화. 서비스 내 ensureLewCanAccess 제거.</p>
     */
    @PreAuthorize("hasAnyRole('ADMIN','SYSTEM_ADMIN') or @appSec.isAssignedLew(#id, authentication)")
    @GetMapping("/applications/{id}")
    public ResponseEntity<AdminApplicationResponse> getApplication(@PathVariable Long id) {
        log.info("Admin get application detail: applicationSeq={}", id);
        AdminApplicationResponse response = adminApplicationService.getApplication(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Get application activity timeline (audit_logs SSOT)
     * GET /api/admin/applications/:id/activity
     *
     * <p>신청 건의 전체 라이프사이클 활동(누가·언제·무엇을 + 자동 동작)을 시간 오름차순으로 반환.
     * ADMIN/SYSTEM_ADMIN 전용 — 감사 로그는 PII(전·후값)를 포함할 수 있어 LEW 에는 노출하지 않는다.</p>
     */
    @PreAuthorize("hasAnyRole('ADMIN','SYSTEM_ADMIN')")
    @GetMapping("/applications/{id}/activity")
    public ResponseEntity<List<com.bluelight.backend.api.audit.ApplicationActivityResponse>> getApplicationActivity(
            @PathVariable Long id) {
        log.info("Admin get application activity timeline: applicationSeq={}", id);
        return ResponseEntity.ok(auditLogService.getApplicationActivity(id));
    }

    /**
     * Update application status
     * PATCH /api/admin/applications/:id/status
     *
     * <p>★ 코드 부채 P0 — LEW 가드는 SpEL @appSec.isAssignedLew 로 단일화.</p>
     */
    @PreAuthorize("hasAnyRole('ADMIN','SYSTEM_ADMIN') or @appSec.isAssignedLew(#id, authentication)")
    @Auditable(action = AuditAction.APPLICATION_STATUS_CHANGE, category = AuditCategory.ADMIN, entityType = "Application")
    @PatchMapping("/applications/{id}/status")
    public ResponseEntity<AdminApplicationResponse> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateStatusRequest request) {
        log.info("Admin update status: applicationSeq={}, status={}", id, request.getStatus());
        AdminApplicationResponse response = adminApplicationService.updateStatus(id, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Confirm offline payment
     * POST /api/admin/applications/:id/payments/confirm
     */
    @PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM_ADMIN')")
    @Auditable(action = AuditAction.PAYMENT_CONFIRMED, category = AuditCategory.ADMIN, entityType = "Application")
    @PostMapping("/applications/{id}/payments/confirm")
    public ResponseEntity<PaymentResponse> confirmPayment(
            @PathVariable Long id,
            @Valid @RequestBody PaymentConfirmRequest request) {
        rateLimiter.checkAndRecord(RATE_TYPE_PAYMENT, "app:" + id, PAYMENT_MAX, PAYMENT_WINDOW_MIN);
        log.info("Admin confirm payment: applicationSeq={}", id);
        PaymentResponse response = adminPaymentService.confirmPayment(id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * ★ Concierge 강화 + 별도 수금 + 영수증 자동 발행 PR-2 — ADMIN 별도 수금 기록 (Application 결제).
     * <p>
     * 스펙: {@code doc/Project Analysis/concierge-flow-and-offline-payment-spec.md} §7.3, §10 AC-A1~A7.
     * D3=C: ADMIN/SYSTEM_ADMIN 만, PENDING_REVIEW/REVISION_REQUESTED/PENDING_PAYMENT 모든 상태에서 호출 가능.
     * 결제 트랜잭션 커밋 후 AFTER_COMMIT 훅에서 영수증 PDF 자동 발행 + 영수증 이메일 발송.
     */
    @PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM_ADMIN')")
    @PostMapping("/applications/{id}/manual-payment")
    public ResponseEntity<com.bluelight.backend.api.admin.dto.ManualPaymentResponse> recordManualPayment(
            Authentication authentication,
            @PathVariable Long id,
            @Valid @RequestBody com.bluelight.backend.api.admin.dto.ManualPaymentRequest request) {
        Long adminUserSeq = (Long) authentication.getPrincipal();
        rateLimiter.checkAndRecord(RATE_TYPE_PAYMENT, "manual-app:" + id, PAYMENT_MAX, PAYMENT_WINDOW_MIN);
        log.info("Admin manual payment: applicationSeq={}, method={}, amount={}, by adminSeq={}",
                id, request.getPaymentMethod(), request.getAmount(), adminUserSeq);
        com.bluelight.backend.api.admin.dto.ManualPaymentResponse response =
                manualPaymentService.recordOfflinePayment(id, request, adminUserSeq);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Complete application and issue licence
     * POST /api/admin/applications/:id/complete
     *
     * <p>★ 코드 부채 P0 — LEW 가드는 SpEL @appSec.isAssignedLew 로 단일화.</p>
     */
    @PreAuthorize("hasAnyRole('ADMIN','SYSTEM_ADMIN') or @appSec.isAssignedLew(#id, authentication)")
    @Auditable(action = AuditAction.APPLICATION_COMPLETED, category = AuditCategory.ADMIN, entityType = "Application")
    @PostMapping("/applications/{id}/complete")
    public ResponseEntity<AdminApplicationResponse> completeApplication(
            @PathVariable Long id,
            @Valid @RequestBody CompleteApplicationRequest request) {
        log.info("Admin complete application: applicationSeq={}, licenseNumber={}", id, request.getLicenseNumber());
        AdminApplicationResponse response = adminApplicationService.completeApplication(id, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Reopen a completed application (ADMIN 전용)
     * POST /api/admin/applications/:id/reopen
     *
     * <p>완료(COMPLETED) 건의 종결 쓰기잠금을 해제해 신청자·LEW 가 파일을 다시 수정할 수 있게 한다.
     * 일반 상태전이와 구분되도록 {@link AuditAction#APPLICATION_REOPENED} 로 감사되어 활동
     * 타임라인에 "완료 건 재개"로 또렷이 남는다. 상태 변경은 ADMIN 권한 한정(LEW 제외).</p>
     */
    @PreAuthorize("hasAnyRole('ADMIN','SYSTEM_ADMIN')")
    @Auditable(action = AuditAction.APPLICATION_REOPENED, category = AuditCategory.ADMIN, entityType = "Application")
    @PostMapping("/applications/{id}/reopen")
    public ResponseEntity<AdminApplicationResponse> reopenApplication(@PathVariable Long id) {
        log.info("Admin reopen application: applicationSeq={}", id);
        AdminApplicationResponse response = adminApplicationService.reopenApplication(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Request revision from applicant
     * POST /api/admin/applications/:id/revision
     *
     * <p>★ 코드 부채 P0 — LEW 가드는 SpEL @appSec.isAssignedLew 로 단일화.</p>
     */
    @PreAuthorize("hasAnyRole('ADMIN','SYSTEM_ADMIN') or @appSec.isAssignedLew(#id, authentication)")
    @Auditable(action = AuditAction.APPLICATION_REVISION_REQUESTED, category = AuditCategory.ADMIN, entityType = "Application")
    @PostMapping("/applications/{id}/revision")
    public ResponseEntity<AdminApplicationResponse> requestRevision(
            @PathVariable Long id,
            @Valid @RequestBody RevisionRequestDto request) {
        log.info("Admin request revision: applicationSeq={}", id);
        AdminApplicationResponse response = adminApplicationService.requestRevision(id, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Approve application and request payment
     * POST /api/admin/applications/:id/approve
     *
     * <p>★ 코드 부채 P0 — LEW 가드는 SpEL @appSec.isAssignedLew 로 단일화.</p>
     */
    @PreAuthorize("hasAnyRole('ADMIN','SYSTEM_ADMIN') or @appSec.isAssignedLew(#id, authentication)")
    @Auditable(action = AuditAction.APPLICATION_APPROVED, category = AuditCategory.ADMIN, entityType = "Application")
    @PostMapping("/applications/{id}/approve")
    public ResponseEntity<AdminApplicationResponse> approveForPayment(@PathVariable Long id) {
        log.info("Admin approve for payment: applicationSeq={}", id);
        AdminApplicationResponse response = adminApplicationService.approveForPayment(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Get payment history for an application
     * GET /api/admin/applications/:id/payments
     *
     * <p>★ 코드 부채 P0 확대 (PR-T8/L-3/P0 후속) — getPayments 만 누락되어 LEW 가 타인
     * 배정 신청서의 결제 이력 열람 가능했음. 동일 SpEL 가드 적용.</p>
     */
    @PreAuthorize("hasAnyRole('ADMIN','SYSTEM_ADMIN') or @appSec.isAssignedLew(#id, authentication)")
    @GetMapping("/applications/{id}/payments")
    public ResponseEntity<List<PaymentResponse>> getPayments(@PathVariable Long id) {
        log.info("Admin get payments: applicationSeq={}", id);
        List<PaymentResponse> payments = adminPaymentService.getPayments(id);
        return ResponseEntity.ok(payments);
    }

    // ============================================================
    // EMA ELISE 제출 추적 — 전이 + 조회 (ema-submission-tracking-spec.md §7, T1~T10)
    // ------------------------------------------------------------
    // 권한(OQ-2): T1~T8·T10 은 completeApplication 과 동일 SpEL — 담당 LEW 본인 + ADMIN/SYSTEM_ADMIN
    //   대행 모두 허용. revert(T9)만 ADMIN/SYSTEM_ADMIN 전용. actorSeq/role 은 Authentication 에서 추출해
    //   서비스에 전달 → 감사로그가 "LEW 본인 vs ADMIN 대행"을 구분(§3.2). 잘못된 전이는 서비스/도메인이
    //   400 INVALID_EMA_TRANSITION 으로 거부(GlobalExceptionHandler 가 코드 그대로 매핑).
    // ============================================================

    /**
     * EMA 제출 (T1): NOT_SUBMITTED → SUBMITTED.
     * POST /api/admin/applications/:id/ema/submit
     */
    @PreAuthorize("hasAnyRole('ADMIN','SYSTEM_ADMIN') or @appSec.isAssignedLew(#id, authentication)")
    @Auditable(action = AuditAction.EMA_SUBMITTED, category = AuditCategory.APPLICATION, entityType = "Application")
    @PostMapping("/applications/{id}/ema/submit")
    public ResponseEntity<EmaSubmissionResponse> markEmaSubmitted(
            Authentication authentication,
            @PathVariable Long id,
            @Valid @RequestBody EmaSubmitRequest request) {
        Long actorSeq = AuthPrincipal.userSeq(authentication);
        String role = AuthPrincipal.role(authentication);
        log.info("EMA submit: applicationSeq={}, by actorSeq={}, role={}", id, actorSeq, role);
        EmaSubmissionResponse response =
                adminApplicationService.markEmaSubmitted(id, request.getEmaReferenceNo(), actorSeq, role);
        return ResponseEntity.ok(response);
    }

    /**
     * EMA 질의 (T2/T4): SUBMITTED/RESUBMITTED → QUERY_RAISED.
     * POST /api/admin/applications/:id/ema/query
     */
    @PreAuthorize("hasAnyRole('ADMIN','SYSTEM_ADMIN') or @appSec.isAssignedLew(#id, authentication)")
    @Auditable(action = AuditAction.EMA_QUERY_RAISED, category = AuditCategory.APPLICATION, entityType = "Application")
    @PostMapping("/applications/{id}/ema/query")
    public ResponseEntity<EmaSubmissionResponse> raiseEmaQuery(
            Authentication authentication,
            @PathVariable Long id,
            @Valid @RequestBody EmaQueryRequest request) {
        Long actorSeq = AuthPrincipal.userSeq(authentication);
        String role = AuthPrincipal.role(authentication);
        log.info("EMA query: applicationSeq={}, by actorSeq={}, role={}", id, actorSeq, role);
        EmaSubmissionResponse response =
                adminApplicationService.raiseEmaQuery(id, request.getQueryNote(), actorSeq, role);
        return ResponseEntity.ok(response);
    }

    /**
     * EMA 재제출 (T3 QUERY_RAISED→ / T10 REJECTED→): → RESUBMITTED.
     * POST /api/admin/applications/:id/ema/resubmit
     */
    @PreAuthorize("hasAnyRole('ADMIN','SYSTEM_ADMIN') or @appSec.isAssignedLew(#id, authentication)")
    @Auditable(action = AuditAction.EMA_RESUBMITTED, category = AuditCategory.APPLICATION, entityType = "Application")
    @PostMapping("/applications/{id}/ema/resubmit")
    public ResponseEntity<EmaSubmissionResponse> resubmitEma(
            Authentication authentication,
            @PathVariable Long id,
            @Valid @RequestBody EmaResubmitRequest request) {
        Long actorSeq = AuthPrincipal.userSeq(authentication);
        String role = AuthPrincipal.role(authentication);
        log.info("EMA resubmit: applicationSeq={}, by actorSeq={}, role={}", id, actorSeq, role);
        EmaSubmissionResponse response =
                adminApplicationService.resubmitEma(id, request.getEmaReferenceNo(), actorSeq, role);
        return ResponseEntity.ok(response);
    }

    /**
     * EMA 승인 (T5/T6): SUBMITTED/RESUBMITTED → APPROVED. 발급(완료)과 분리된 상태 표기.
     * POST /api/admin/applications/:id/ema/approve
     */
    @PreAuthorize("hasAnyRole('ADMIN','SYSTEM_ADMIN') or @appSec.isAssignedLew(#id, authentication)")
    @Auditable(action = AuditAction.EMA_APPROVED, category = AuditCategory.APPLICATION, entityType = "Application")
    @PostMapping("/applications/{id}/ema/approve")
    public ResponseEntity<EmaSubmissionResponse> approveEma(
            Authentication authentication,
            @PathVariable Long id) {
        Long actorSeq = AuthPrincipal.userSeq(authentication);
        String role = AuthPrincipal.role(authentication);
        log.info("EMA approve: applicationSeq={}, by actorSeq={}, role={}", id, actorSeq, role);
        EmaSubmissionResponse response = adminApplicationService.approveEma(id, actorSeq, role);
        return ResponseEntity.ok(response);
    }

    /**
     * EMA 반려 (T7): SUBMITTED/RESUBMITTED → REJECTED. 종착 아님(T10 재진입 가능), App 은 IN_PROGRESS 유지.
     * POST /api/admin/applications/:id/ema/reject
     */
    @PreAuthorize("hasAnyRole('ADMIN','SYSTEM_ADMIN') or @appSec.isAssignedLew(#id, authentication)")
    @Auditable(action = AuditAction.EMA_REJECTED, category = AuditCategory.APPLICATION, entityType = "Application")
    @PostMapping("/applications/{id}/ema/reject")
    public ResponseEntity<EmaSubmissionResponse> rejectEma(
            Authentication authentication,
            @PathVariable Long id,
            @Valid @RequestBody EmaRejectRequest request) {
        Long actorSeq = AuthPrincipal.userSeq(authentication);
        String role = AuthPrincipal.role(authentication);
        log.info("EMA reject: applicationSeq={}, by actorSeq={}, role={}", id, actorSeq, role);
        EmaSubmissionResponse response =
                adminApplicationService.rejectEma(id, request.getReason(), actorSeq, role);
        return ResponseEntity.ok(response);
    }

    /**
     * EMA 철회 (T8): SUBMITTED/QUERY_RAISED/RESUBMITTED → WITHDRAWN.
     * POST /api/admin/applications/:id/ema/withdraw
     */
    @PreAuthorize("hasAnyRole('ADMIN','SYSTEM_ADMIN') or @appSec.isAssignedLew(#id, authentication)")
    @Auditable(action = AuditAction.EMA_WITHDRAWN, category = AuditCategory.APPLICATION, entityType = "Application")
    @PostMapping("/applications/{id}/ema/withdraw")
    public ResponseEntity<EmaSubmissionResponse> withdrawEma(
            Authentication authentication,
            @PathVariable Long id) {
        Long actorSeq = AuthPrincipal.userSeq(authentication);
        String role = AuthPrincipal.role(authentication);
        log.info("EMA withdraw: applicationSeq={}, by actorSeq={}, role={}", id, actorSeq, role);
        EmaSubmissionResponse response = adminApplicationService.withdrawEma(id, actorSeq, role);
        return ResponseEntity.ok(response);
    }

    /**
     * EMA 결정 되돌리기 (T9): APPROVED/WITHDRAWN → 직전 상태 복원. ADMIN/SYSTEM_ADMIN 전용(오기입 정정).
     * POST /api/admin/applications/:id/ema/revert
     */
    @PreAuthorize("hasAnyRole('ADMIN','SYSTEM_ADMIN')")
    @Auditable(action = AuditAction.EMA_DECISION_REVERTED, category = AuditCategory.APPLICATION, entityType = "Application")
    @PostMapping("/applications/{id}/ema/revert")
    public ResponseEntity<EmaSubmissionResponse> revertEmaDecision(
            Authentication authentication,
            @PathVariable Long id) {
        Long actorSeq = AuthPrincipal.userSeq(authentication);
        String role = AuthPrincipal.role(authentication);
        log.info("EMA revert: applicationSeq={}, by actorSeq={}, role={}", id, actorSeq, role);
        EmaSubmissionResponse response = adminApplicationService.revertEmaDecision(id, actorSeq, role);
        return ResponseEntity.ok(response);
    }

    /**
     * EMA 제출 추적 조회.
     * GET /api/admin/applications/:id/ema
     */
    @PreAuthorize("hasAnyRole('ADMIN','SYSTEM_ADMIN') or @appSec.isAssignedLew(#id, authentication)")
    @GetMapping("/applications/{id}/ema")
    public ResponseEntity<EmaSubmissionResponse> getEmaSubmission(@PathVariable Long id) {
        log.info("EMA get: applicationSeq={}", id);
        EmaSubmissionResponse response = adminApplicationService.getEmaSubmission(id);
        return ResponseEntity.ok(response);
    }
}

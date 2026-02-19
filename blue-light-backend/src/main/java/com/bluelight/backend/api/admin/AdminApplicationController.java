package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.*;
import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.audit.Auditable;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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

    /**
     * Get admin dashboard summary
     * GET /api/admin/dashboard
     */
    @GetMapping("/dashboard")
    public ResponseEntity<AdminDashboardResponse> getDashboard() {
        log.info("Admin dashboard requested");
        AdminDashboardResponse response = adminApplicationService.getDashboardSummary();
        return ResponseEntity.ok(response);
    }

    /**
     * Get all applications (paginated, optional status filter)
     * GET /api/admin/applications?status=PENDING_PAYMENT&page=0&size=20
     */
    @GetMapping("/applications")
    public ResponseEntity<Page<AdminApplicationResponse>> getAllApplications(
            @RequestParam(required = false) ApplicationStatus status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int validPage = Math.max(0, page);
        int validSize = Math.min(Math.max(1, size), 100);
        log.info("Admin get all applications: status={}, search={}, page={}, size={}", status, search, validPage, validSize);
        Pageable pageable = PageRequest.of(validPage, validSize);
        Page<AdminApplicationResponse> applications = adminApplicationService.getAllApplications(status, search, pageable);
        return ResponseEntity.ok(applications);
    }

    /**
     * Get application detail (admin view)
     * GET /api/admin/applications/:id
     */
    @GetMapping("/applications/{id}")
    public ResponseEntity<AdminApplicationResponse> getApplication(@PathVariable Long id) {
        log.info("Admin get application detail: applicationSeq={}", id);
        AdminApplicationResponse response = adminApplicationService.getApplication(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Update application status
     * PATCH /api/admin/applications/:id/status
     */
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
    @Auditable(action = AuditAction.PAYMENT_CONFIRMED, category = AuditCategory.ADMIN, entityType = "Application")
    @PostMapping("/applications/{id}/payments/confirm")
    public ResponseEntity<PaymentResponse> confirmPayment(
            @PathVariable Long id,
            @Valid @RequestBody PaymentConfirmRequest request) {
        log.info("Admin confirm payment: applicationSeq={}", id);
        PaymentResponse response = adminPaymentService.confirmPayment(id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Complete application and issue licence
     * POST /api/admin/applications/:id/complete
     */
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
     * Request revision from applicant
     * POST /api/admin/applications/:id/revision
     */
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
     */
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
     */
    @GetMapping("/applications/{id}/payments")
    public ResponseEntity<List<PaymentResponse>> getPayments(@PathVariable Long id) {
        log.info("Admin get payments: applicationSeq={}", id);
        List<PaymentResponse> payments = adminPaymentService.getPayments(id);
        return ResponseEntity.ok(payments);
    }
}

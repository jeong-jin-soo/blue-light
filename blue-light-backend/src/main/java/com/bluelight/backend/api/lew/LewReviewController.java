package com.bluelight.backend.api.lew;

import com.bluelight.backend.api.application.dto.ApplicationResponse;
import com.bluelight.backend.api.lew.dto.CertificateOfFitnessRequest;
import com.bluelight.backend.api.lew.dto.CertificateOfFitnessResponse;
import com.bluelight.backend.api.lew.dto.LewApplicationResponse;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.audit.Auditable;
import com.bluelight.backend.service.cof.LewReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * LEW Review Form 전용 API (lew-review-form-spec.md §3).
 *
 * <p>경로는 {@code /api/lew/**} — SecurityConfig에서 URL 단 {@code hasRole("LEW")} 일차 방어,
 * 메서드별 {@code @PreAuthorize("@appSec.isAssignedLew(#id, authentication)")}로 배정 여부 검증.</p>
 *
 * <p>감사 로그는 {@link Auditable} AOP에 위임한다. 상세 {@code APPLICATION_VIEWED_BY_LEW} /
 * CoF CRUD 이벤트 / finalize 전이까지 모두 enum에 정의되어 있다 (P1.A).</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/lew/applications")
@RequiredArgsConstructor
@PreAuthorize("hasRole('LEW')")
public class LewReviewController {

    private final LewReviewService lewReviewService;

    /** §3.1 — 배정 신청 상세 조회. */
    @GetMapping("/{id}")
    @PreAuthorize("@appSec.isAssignedLew(#id, authentication)")
    @Auditable(action = AuditAction.APPLICATION_VIEWED_BY_LEW,
            category = AuditCategory.APPLICATION, entityType = "Application")
    public ResponseEntity<LewApplicationResponse> getAssignedApplication(
            @PathVariable("id") Long id,
            Authentication authentication) {
        Long lewUserSeq = (Long) authentication.getPrincipal();
        log.info("LEW getAssignedApplication: lewUserSeq={}, applicationSeq={}", lewUserSeq, id);
        return ResponseEntity.ok(lewReviewService.getAssignedApplication(id, lewUserSeq));
    }

    /**
     * §3.2 — CoF Draft Save/Upsert.
     *
     * <p>감사 action은 CoF 존재 유무로 CREATED/UPDATED를 분기해야 하지만,
     * {@link Auditable}은 정적으로 한 개만 지정 가능하므로 Upsert 경로는 `_UPDATED`로 통일한다.
     * 최초 생성 여부는 감사 로그의 {@code before_value}가 null인지로 구분 가능 (AOP가 요청 body만 기록).
     * 정말 정교하게 분기하려면 Auditable 대신 서비스에서 직접 AuditLogService 호출하면 된다 — P1.C에서 재고.</p>
     */
    @PutMapping("/{id}/cof")
    @PreAuthorize("@appSec.isAssignedLew(#id, authentication)")
    @Auditable(action = AuditAction.CERTIFICATE_OF_FITNESS_UPDATED,
            category = AuditCategory.APPLICATION, entityType = "CertificateOfFitness")
    public ResponseEntity<CertificateOfFitnessResponse> saveDraftCof(
            @PathVariable("id") Long id,
            @Valid @RequestBody CertificateOfFitnessRequest request,
            Authentication authentication) {
        Long lewUserSeq = (Long) authentication.getPrincipal();
        log.info("LEW saveDraftCof: lewUserSeq={}, applicationSeq={}", lewUserSeq, id);
        CertificateOfFitnessResponse res = lewReviewService.saveDraftCof(id, lewUserSeq, request);
        return ResponseEntity.ok(res);
    }

    /**
     * §3.3 — CoF 확정 (PR3 옵션 R: 결제 후 단계).
     *
     * <p>PR3 이전: PENDING_REVIEW → PENDING_PAYMENT 전이를 일으켰음(도메인 부정합).</p>
     * <p>PR3 이후: PAID/IN_PROGRESS 상태에서만 호출 가능하며 status 전이는 발생하지 않는다.
     * SS 638 §13 준수.</p>
     */
    @PostMapping("/{id}/cof/finalize")
    @PreAuthorize("@appSec.isAssignedLew(#id, authentication)")
    @Auditable(action = AuditAction.CERTIFICATE_OF_FITNESS_FINALIZED,
            category = AuditCategory.APPLICATION, entityType = "CertificateOfFitness")
    public ResponseEntity<ApplicationResponse> finalizeCof(
            @PathVariable("id") Long id,
            Authentication authentication) {
        Long lewUserSeq = (Long) authentication.getPrincipal();
        log.info("LEW finalizeCof: lewUserSeq={}, applicationSeq={}", lewUserSeq, id);
        return ResponseEntity.ok(lewReviewService.finalizeCof(id, lewUserSeq));
    }

    /**
     * PR3: LEW가 명시적으로 결제 요청을 트리거 (옵션 R).
     *
     * <p>Phase 1(검토 + 서류 + kVA) 종료 후, LEW가 호출하여 status를
     * {@code PENDING_REVIEW/REVISION_REQUESTED → PENDING_PAYMENT}로 전이.
     * ADMIN의 별도 {@code approveForPayment} 흐름과 공존하며, race 발생 시 두 번째 호출은
     * {@code INVALID_STATUS_TRANSITION}(409)으로 거부된다.</p>
     *
     * <h3>가드 위반 코드 (모두 HTTP 409)</h3>
     * <ul>
     *   <li>{@code INVALID_STATUS_TRANSITION} — status 전제 위반</li>
     *   <li>{@code KVA_NOT_CONFIRMED} — kVA 미확정</li>
     *   <li>{@code DOCUMENT_REQUESTS_PENDING} — 미해결 서류 요청 존재</li>
     * </ul>
     */
    @PostMapping("/{id}/request-payment")
    @PreAuthorize("@appSec.isAssignedLew(#id, authentication)")
    @Auditable(action = AuditAction.APPLICATION_PAYMENT_REQUESTED_BY_LEW,
            category = AuditCategory.APPLICATION, entityType = "Application")
    public ResponseEntity<ApplicationResponse> requestPayment(
            @PathVariable("id") Long id,
            Authentication authentication) {
        Long lewUserSeq = (Long) authentication.getPrincipal();
        log.info("LEW requestPayment: lewUserSeq={}, applicationSeq={}", lewUserSeq, id);
        return ResponseEntity.ok(lewReviewService.requestPayment(id, lewUserSeq));
    }
}

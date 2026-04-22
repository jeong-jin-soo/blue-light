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

    /** §3.3 — CoF 확정 (status PENDING_REVIEW → PENDING_PAYMENT). */
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
}

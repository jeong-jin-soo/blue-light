package com.bluelight.backend.api.lew;

import com.bluelight.backend.api.application.dto.ApplicationResponse;
import com.bluelight.backend.api.lew.dto.LewApplicationResponse;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.audit.Auditable;
import com.bluelight.backend.service.lewreview.LewReviewService;
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
 * <p>감사 로그는 {@link Auditable} AOP에 위임한다 ({@code APPLICATION_VIEWED_BY_LEW},
 * {@code APPLICATION_PAYMENT_REQUESTED_BY_LEW}).</p>
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
     *   <li>{@code SLD_ALREADY_LEW} — addSldFee=true 인데 이미 REQUEST_LEW</li>
     * </ul>
     * <p>문서요청·LoA 상태는 결제 요청을 막지 않는다 (2026-06-18 결정 — kVA 확정이 충분조건).</p>
     *
     * <p>E1 (sld-lew-conversion-fee-spec.md §4): {@code addSldFee=true} 면 결제 요청 직전에
     * SLD self-upload → LEW 작성 전환 + SLD 작성비를 견적에 가산한다 (결제 전이라 정산 원장 없음).</p>
     */
    @PostMapping("/{id}/request-payment")
    @PreAuthorize("@appSec.isAssignedLew(#id, authentication)")
    @Auditable(action = AuditAction.APPLICATION_PAYMENT_REQUESTED_BY_LEW,
            category = AuditCategory.APPLICATION, entityType = "Application")
    public ResponseEntity<ApplicationResponse> requestPayment(
            @PathVariable("id") Long id,
            @RequestParam(name = "addSldFee", defaultValue = "false") boolean addSldFee,
            Authentication authentication) {
        Long lewUserSeq = (Long) authentication.getPrincipal();
        log.info("LEW requestPayment: lewUserSeq={}, applicationSeq={}, addSldFee={}", lewUserSeq, id, addSldFee);
        return ResponseEntity.ok(lewReviewService.requestPayment(id, lewUserSeq, addSldFee));
    }
}

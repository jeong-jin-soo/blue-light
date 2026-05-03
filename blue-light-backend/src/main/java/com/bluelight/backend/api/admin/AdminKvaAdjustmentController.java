package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.KvaAdjustmentHistoryItem;
import com.bluelight.backend.api.admin.dto.KvaPostPaymentOverrideRequest;
import com.bluelight.backend.api.admin.dto.KvaPostPaymentOverrideResponse;
import com.bluelight.backend.api.admin.dto.KvaSettlementUpdateRequest;
import com.bluelight.backend.service.kva.KvaPostPaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 결제 후 kVA 사후 변경 ADMIN 전용 컨트롤러.
 *
 * <p>스펙: {@code doc/Project Analysis/kva-postpayment-adjustment-spec.md} §4.1, §4.3.</p>
 *
 * <p>기존 {@link com.bluelight.backend.api.admin.ApplicationKvaController} 의 {@code PATCH /kva}
 * 는 결제 전 흐름 전용이며 그대로 유지. 본 컨트롤러는 결제 후(PAID/IN_PROGRESS/COMPLETED) 변경만
 * 처리한다.</p>
 *
 * <h2>엔드포인트</h2>
 * <ul>
 *   <li>{@code POST /api/admin/applications/{id}/kva-override-postpayment} — PR-1 ADMIN 직접 변경</li>
 *   <li>{@code GET  /api/admin/applications/{id}/kva-adjustments} — PR-4 이력 조회 (ADMIN + assigned LEW)</li>
 *   <li>{@code PATCH /api/admin/applications/{id}/kva-adjustments/{adjustmentSeq}/settlement} — PR-4 정산 마킹</li>
 * </ul>
 *
 * <p>본 컨트롤러는 메서드별 {@code @PreAuthorize} 로 권한을 제어한다 — GET 은 LEW 도 허용,
 * POST/PATCH 는 ADMIN/SYSTEM_ADMIN 전용. {@code @appSec.isAssignedLew} 표현식이 GET 에서
 * ADMIN 권한과 OR 로 결합되도록 SpEL 표현식을 사용.</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AdminKvaAdjustmentController {

    private final KvaPostPaymentService kvaPostPaymentService;

    /**
     * §4.1 PR-1 — ADMIN 직접 변경 (결제 후 kVA 사후 변경).
     */
    @PostMapping("/api/admin/applications/{id}/kva-override-postpayment")
    @PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<KvaPostPaymentOverrideResponse> overrideKvaPostPayment(
            Authentication authentication,
            @PathVariable Long id,
            @Valid @RequestBody KvaPostPaymentOverrideRequest request) {
        Long adminUserSeq = (Long) authentication.getPrincipal();
        log.info("POST /kva-override-postpayment: applicationId={}, adminSeq={}, newKva={}",
                id, adminUserSeq, request.getNewKva());

        KvaPostPaymentOverrideResponse response =
                kvaPostPaymentService.overrideKva(id, request, adminUserSeq);
        return ResponseEntity.ok(response);
    }

    /**
     * §8 PR-4 — 결제 후 kVA 사후 변경 이력 조회.
     *
     * <p>권한: ADMIN/SYSTEM_ADMIN 또는 신청에 배정된 LEW.</p>
     *
     * <p>응답: 시간 내림차순 이력 목록. {@code lewRequestSeq} 로 LEW 요청 row 와 ADMIN 변경 row 가
     * 묶이며, 프론트는 이를 timeline 으로 표시한다. 빈 배열도 정상 응답 (200 OK).</p>
     */
    @GetMapping("/api/admin/applications/{id}/kva-adjustments")
    @PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM_ADMIN') or @appSec.isAssignedLew(#id, authentication)")
    public ResponseEntity<List<KvaAdjustmentHistoryItem>> getAdjustmentHistory(
            @PathVariable Long id) {
        log.info("GET /kva-adjustments: applicationId={}", id);
        List<KvaAdjustmentHistoryItem> items = kvaPostPaymentService.getAdjustmentHistory(id);
        return ResponseEntity.ok(items);
    }

    /**
     * §4.3 PR-4 — Settlement 마킹.
     *
     * <h3>가드 위반 코드</h3>
     * <ul>
     *   <li>404 {@code KVA_ADJUSTMENT_NOT_FOUND} — row 미존재 또는 다른 application 의 row</li>
     *   <li>409 {@code KVA_SETTLEMENT_NOT_APPLICABLE} — row.status 가 APPLIED/RESOLVED_BY_ADMIN_OVERRIDE 가 아님</li>
     *   <li>409 {@code KVA_SETTLEMENT_ALREADY_FINALIZED} — D6 거부</li>
     *   <li>400 {@code KVA_SETTLEMENT_INVALID_VALUE} — paymentAdjustment 가 PENDING/null</li>
     * </ul>
     */
    @PatchMapping("/api/admin/applications/{id}/kva-adjustments/{adjustmentSeq}/settlement")
    @PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<KvaAdjustmentHistoryItem> markSettlement(
            Authentication authentication,
            @PathVariable Long id,
            @PathVariable Long adjustmentSeq,
            @Valid @RequestBody KvaSettlementUpdateRequest request) {
        Long adminUserSeq = (Long) authentication.getPrincipal();
        log.info("PATCH /kva-adjustments/{}/settlement: applicationId={}, adminSeq={}, paymentAdjustment={}",
                adjustmentSeq, id, adminUserSeq, request.getPaymentAdjustment());

        KvaAdjustmentHistoryItem response =
                kvaPostPaymentService.markSettlement(id, adjustmentSeq, request, adminUserSeq);
        return ResponseEntity.ok(response);
    }
}

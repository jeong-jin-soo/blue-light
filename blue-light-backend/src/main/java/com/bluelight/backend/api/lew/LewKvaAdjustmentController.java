package com.bluelight.backend.api.lew;

import com.bluelight.backend.api.lew.dto.LewKvaAdjustmentRequest;
import com.bluelight.backend.api.lew.dto.LewKvaAdjustmentResponse;
import com.bluelight.backend.service.kva.KvaPostPaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 결제 후 kVA 사후 변경 — LEW 요청 흐름 컨트롤러 (PR-3).
 *
 * <p>Endpoint: {@code POST /api/lew/applications/{id}/kva-adjustment-request}<br>
 * 스펙: {@code doc/Project Analysis/kva-postpayment-adjustment-spec.md} §4.2.</p>
 *
 * <p>{@code @PreAuthorize("hasRole('LEW')")} 일차 방어 + 메서드 단
 * {@code @appSec.isAssignedLew(#id, authentication)} 으로 배정 LEW 만 호출 가능 (AC-L2). </p>
 *
 * <p>본 컨트롤러는 LEW 의 <b>요청만</b> 처리한다 — 실제 kVA 변경은 ADMIN 의
 * {@code POST /api/admin/applications/{id}/kva-override-postpayment} 에서 수행된다.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/lew/applications")
@RequiredArgsConstructor
@PreAuthorize("hasRole('LEW')")
public class LewKvaAdjustmentController {

    private final KvaPostPaymentService kvaPostPaymentService;

    /**
     * §4.2 — LEW 가 결제 후 kVA 변경을 ADMIN 에게 요청.
     *
     * <h3>가드 위반 코드</h3>
     * <ul>
     *   <li>403 {@code APPLICATION_NOT_ASSIGNED} — 배정 LEW 가 아님 ({@code @PreAuthorize})</li>
     *   <li>409 {@code KVA_NOT_POSTPAYMENT} — PRE-PAYMENT 상태</li>
     *   <li>409 {@code KVA_ADJUSTMENT_NOT_ALLOWED_EXPIRED} — EXPIRED 상태</li>
     *   <li>409 {@code KVA_ADJUSTMENT_REQUEST_ALREADY_PENDING} — 동일 application 에 PENDING 요청 존재 (D4)</li>
     *   <li>400 {@code KVA_NO_CHANGE} — 동일 proposedKva</li>
     *   <li>400 {@code INVALID_KVA_TIER} — master_prices 미존재</li>
     * </ul>
     */
    @PostMapping("/{id}/kva-adjustment-request")
    @PreAuthorize("@appSec.isAssignedLew(#id, authentication)")
    public ResponseEntity<LewKvaAdjustmentResponse> requestKvaAdjustment(
            Authentication authentication,
            @PathVariable("id") Long id,
            @Valid @RequestBody LewKvaAdjustmentRequest request) {
        Long lewUserSeq = (Long) authentication.getPrincipal();
        log.info("POST /lew/applications/{}/kva-adjustment-request: lewSeq={}, proposedKva={}",
                id, lewUserSeq, request.getProposedKva());

        LewKvaAdjustmentResponse response =
                kvaPostPaymentService.requestAdjustmentByLew(id, lewUserSeq, request);
        return ResponseEntity.ok(response);
    }
}

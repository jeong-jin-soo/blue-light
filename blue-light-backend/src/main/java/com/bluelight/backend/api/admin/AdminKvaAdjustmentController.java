package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.KvaPostPaymentOverrideRequest;
import com.bluelight.backend.api.admin.dto.KvaPostPaymentOverrideResponse;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 결제 후 kVA 사후 변경 ADMIN 전용 컨트롤러 (PR-1).
 *
 * <p>Endpoint: {@code POST /api/admin/applications/{id}/kva-override-postpayment}<br>
 * 스펙: {@code doc/Project Analysis/kva-postpayment-adjustment-spec.md} §4.1.</p>
 *
 * <p>기존 {@link ApplicationKvaController} 의 {@code PATCH /kva} 는 결제 전 흐름 전용이며 그대로 유지.
 * 본 컨트롤러는 결제 후(PAID/IN_PROGRESS/COMPLETED) 변경만 처리한다.</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM_ADMIN')")
public class AdminKvaAdjustmentController {

    private final KvaPostPaymentService kvaPostPaymentService;

    @PostMapping("/api/admin/applications/{id}/kva-override-postpayment")
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
}

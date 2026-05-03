package com.bluelight.backend.api.lew.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 결제 후 kVA 변경을 LEW 가 ADMIN 에게 요청하는 DTO (PR-3).
 *
 * <p>Endpoint: {@code POST /api/lew/applications/{id}/kva-adjustment-request}<br>
 * 스펙: {@code doc/Project Analysis/kva-postpayment-adjustment-spec.md} §4.2.</p>
 *
 * <p>본 요청은 단순 제안 — 시스템은 {@code Application.selectedKva} 를 변경하지 않으며,
 * {@code KvaAdjustmentRecord} 를 status={@code PENDING_ADMIN_REVIEW} 로 작성하고
 * ADMIN 에게 알림만 발송한다.</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class LewKvaAdjustmentRequest {

    /**
     * LEW 가 제안하는 kVA tier. {@code master_prices} 에 등록된 활성 tier 만 허용.
     * 서비스에서 {@link com.bluelight.backend.domain.price.MasterPriceRepository#findByKva}
     * 로 검증 — 없으면 400 {@code INVALID_KVA_TIER}.
     */
    @NotNull(message = "proposedKva is required")
    @Positive(message = "proposedKva must be positive")
    private Integer proposedKva;

    /**
     * 변경 요청 사유 (필수). 운영상 LEW 의 책임 추적을 위해 필수 입력.
     */
    @NotBlank(message = "reason is required")
    @Size(max = 1000, message = "reason must be 1000 characters or less")
    private String reason;
}

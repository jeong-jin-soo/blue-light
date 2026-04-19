package com.bluelight.backend.api.admin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Phase 5 — LEW/ADMIN kVA 확정 요청 DTO.
 *
 * <p>Endpoint: {@code PATCH /api/admin/applications/{id}/kva}
 * <p>Spec: {@code phase5-kva-ux/01-spec.md §4}, {@code 03-security-review.md §1~§4}
 */
@Getter
@Setter
@NoArgsConstructor
public class ConfirmKvaRequest {

    /**
     * 확정할 kVA tier. 허용 값(MasterPrice 테이블 기준)만 통과하며,
     * 서버에서 {@link com.bluelight.backend.domain.price.MasterPriceRepository#findByKva} 로
     * 유효성 재검증한다 — 없으면 400 {@code INVALID_KVA_TIER}.
     */
    @NotNull(message = "selectedKva is required")
    @Positive(message = "selectedKva must be positive")
    private Integer selectedKva;

    /**
     * LEW 확정 근거 메모 (선택).
     * <p>UI 권장 최소 10자 — 서버에서는 길이 상한만 검증.
     * <p>PDPA: NRIC/UEN 등 민감정보 입력 금지 (UI helper 로 안내).
     */
    @Size(max = 1000, message = "note must be 1000 characters or less")
    private String note;
}

package com.bluelight.backend.domain.application;

/**
 * kVA 확정 상태 (Phase 5)
 *
 * <ul>
 *   <li>{@link #UNKNOWN} — 신청자가 "I don't know"를 선택. LEW 확정 필요.
 *       이 상태에서는 {@code selectedKva=55} (placeholder)로 저장되며,
 *       결제 단계(approveForPayment) 진입이 차단된다.</li>
 *   <li>{@link #CONFIRMED} — 사용자 직접 선택(USER_INPUT) 또는 LEW 확정(LEW_VERIFIED)
 *       으로 값이 확정된 상태. 기본값. 결제 단계 진입 가능.</li>
 * </ul>
 *
 * 전이는 단방향: {@code UNKNOWN → CONFIRMED} ({@code PATCH /api/admin/applications/{id}/kva}).
 * 역전이는 명시적으로 금지 (감사 로그로만 이전 상태 추적).
 */
public enum KvaStatus {
    /** 신청자가 kVA를 모름 — LEW 확정 대기. */
    UNKNOWN,

    /** 값이 확정됨 (사용자 또는 LEW). */
    CONFIRMED;

    /**
     * UNKNOWN 에서만 CONFIRMED 로 전이 가능.
     * CONFIRMED 에서 CONFIRMED 로의 재확정은 도메인 레벨이 아닌
     * 컨트롤러의 {@code force} 플래그로만 허용한다(감사 로그 분리 목적).
     */
    public boolean canTransitionTo(KvaStatus target) {
        if (target == null) {
            return false;
        }
        return this == UNKNOWN && target == CONFIRMED;
    }
}

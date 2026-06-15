package com.bluelight.backend.domain.application;

/**
 * kVA 확정 상태 (Phase 5)
 *
 * <ul>
 *   <li>{@link #UNKNOWN} — LEW 미확정 상태. 신청자가 "I don't know"를 선택했거나(이 경우
 *       {@code selectedKva=55} placeholder + {@code kvaSource=null}), 신청자가 값을 직접
 *       신고했지만 아직 LEW 가 확정하지 않은 경우({@code kvaSource=USER_INPUT} + 신고값 보존)를
 *       모두 포함한다. 결제 단계(approveForPayment) 진입이 차단된다.
 *       <b>"신청자가 kVA 를 적어 올렸다"고 LEW 확정 상태가 되지 않는다.</b></li>
 *   <li>{@link #CONFIRMED} — LEW(또는 ADMIN)가 {@code PATCH /kva} 로 확정한 상태
 *       ({@code kvaSource=LEW_VERIFIED}). 결제 단계 진입 가능.</li>
 * </ul>
 *
 * 정상 전이: {@code UNKNOWN → CONFIRMED} (LEW 확정). 결제 전에는 CONFIRMED 에서도 재확정(값 변경)
 * 가능 — 정책/권한은 {@code ApplicationKvaService} 가 관리한다.
 */
public enum KvaStatus {
    /** LEW 미확정 — 신청자 미입력 또는 신청자 신고값(USER_INPUT)이나 아직 LEW 확정 전. */
    UNKNOWN,

    /** LEW(또는 ADMIN)가 확정함 (kvaSource=LEW_VERIFIED). */
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

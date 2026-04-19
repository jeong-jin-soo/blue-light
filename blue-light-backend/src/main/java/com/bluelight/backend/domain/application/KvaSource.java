package com.bluelight.backend.domain.application;

/**
 * kVA 값이 어떻게 기록되었는지 출처 표시 (Phase 5).
 *
 * <ul>
 *   <li>{@link #USER_INPUT} — 신청자가 드롭다운에서 tier 를 직접 선택.
 *       {@code kvaConfirmedBy} 는 기록하지 않는다.</li>
 *   <li>{@link #LEW_VERIFIED} — LEW(또는 ADMIN)가 {@code PATCH /kva} 로 확정.
 *       {@code kvaConfirmedBy}, {@code kvaConfirmedAt} 가 반드시 설정된다.</li>
 * </ul>
 *
 * {@code kvaStatus=UNKNOWN} 일 때는 NULL 로 저장한다
 * (schema CHECK 제약: {@code kva_status='UNKNOWN' OR kva_source IS NOT NULL}).
 */
public enum KvaSource {
    USER_INPUT,
    LEW_VERIFIED
}

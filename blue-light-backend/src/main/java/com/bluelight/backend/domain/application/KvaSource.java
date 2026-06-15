package com.bluelight.backend.domain.application;

/**
 * kVA 값이 어떻게 기록되었는지 출처 표시 (Phase 5).
 *
 * <ul>
 *   <li>{@link #USER_INPUT} — 신청자가 드롭다운에서 tier 를 직접 신고한 값. 신고만으로는
 *       LEW 확정이 아니므로 {@code kvaStatus} 는 {@code UNKNOWN}(LEW 미확정)으로 남고,
 *       {@code kvaConfirmedBy} 는 기록하지 않는다. LEW 가 확정하면 {@code LEW_VERIFIED} 로 바뀐다.</li>
 *   <li>{@link #LEW_VERIFIED} — LEW(또는 ADMIN)가 {@code PATCH /kva} 로 확정.
 *       {@code kvaStatus=CONFIRMED} + {@code kvaConfirmedBy}/{@code kvaConfirmedAt} 설정.</li>
 * </ul>
 *
 * 신청자가 "I don't know" 를 선택한 경우에만 {@code kvaSource=null}(+ placeholder 값)로 저장한다.
 */
public enum KvaSource {
    USER_INPUT,
    LEW_VERIFIED
}

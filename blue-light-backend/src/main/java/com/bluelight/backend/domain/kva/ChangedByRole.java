package com.bluelight.backend.domain.kva;

/**
 * KvaAdjustmentRecord 변경 주체 역할.
 *
 * <ul>
 *   <li>{@link #ADMIN} — ADMIN 이 직접 적용한 변경 row.</li>
 *   <li>{@link #LEW} — LEW 가 보낸 변경 요청 row (PR-3 에서 사용). PR-1 범위에서는 미사용.</li>
 * </ul>
 *
 * <p>스펙: {@code doc/Project Analysis/kva-postpayment-adjustment-spec.md} §5.1.</p>
 */
public enum ChangedByRole {
    LEW,
    ADMIN
}

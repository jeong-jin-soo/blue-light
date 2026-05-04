package com.bluelight.backend.domain.manualemail;

/**
 * ADMIN 수동 이메일의 수신자 유형.
 *
 * <p>스펙: {@code doc/Project Analysis/admin-manual-email-spec.md} §4 데이터 모델.</p>
 *
 * <ul>
 *   <li>{@link #APPLICANT} — 시스템 사용자(role=APPLICANT) 단일/다수 수신.</li>
 *   <li>{@link #LEW} — 시스템 사용자(role=LEW) 단일/다수 수신.</li>
 *   <li>{@link #EXTERNAL} — 시스템 미등록 임의 이메일 단일/다수 수신.</li>
 *   <li>{@link #MULTI} — 위 3종 혼합 다수 (PR-2 에서 활성화). PR-1 은 컨트롤러에서 거부.</li>
 * </ul>
 */
public enum RecipientType {
    APPLICANT,
    LEW,
    EXTERNAL,
    MULTI
}

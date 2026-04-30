package com.bluelight.backend.domain.kva;

/**
 * 결제 후 kVA 사후 변경 row 의 상태.
 *
 * <p>스펙: {@code doc/Project Analysis/kva-postpayment-adjustment-spec.md} §5.1.</p>
 *
 * <ul>
 *   <li>{@link #PENDING_ADMIN_REVIEW} — LEW 가 보낸 변경 요청, ADMIN 검토 대기. (PR-3 에서 사용)</li>
 *   <li>{@link #APPLIED} — ADMIN 이 직접 변경 적용 완료. PR-1 의 정상 흐름.</li>
 *   <li>{@link #RESOLVED_BY_ADMIN_OVERRIDE} — LEW 요청 row 가 ADMIN 의 별개 변경으로 해소됨. (PR-3)</li>
 *   <li>{@link #REJECTED} — ADMIN 이 LEW 요청을 거부. (PR-3)</li>
 *   <li>{@link #CANCELLED} — LEW 가 본인 요청을 취소. (PR-3)</li>
 * </ul>
 */
public enum KvaAdjustmentStatus {
    PENDING_ADMIN_REVIEW,
    APPLIED,
    RESOLVED_BY_ADMIN_OVERRIDE,
    REJECTED,
    CANCELLED
}

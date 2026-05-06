package com.bluelight.backend.domain.manualemail;

/**
 * ADMIN 수동 이메일 발송 상태.
 *
 * <p>스펙: {@code doc/Project Analysis/admin-manual-email-spec.md} §4.</p>
 *
 * <ul>
 *   <li>{@link #PENDING} — DB row 가 저장되었으나 SMTP 발송이 아직 끝나지 않은 상태.
 *       {@code @TransactionalEventListener(AFTER_COMMIT)} 가 SMTP 호출 후 SENT/FAILED 로 갱신.</li>
 *   <li>{@link #SENT} — 모든 수신자에게 SMTP 발송 성공.</li>
 *   <li>{@link #PARTIAL_FAILED} — 일부 수신자 실패 (PR-2 에서 의미 있음, PR-1 은 단일 수신자라 미발생).</li>
 *   <li>{@link #FAILED} — 모든 수신자 SMTP 실패.</li>
 * </ul>
 */
public enum DispatchStatus {
    PENDING,
    SENT,
    PARTIAL_FAILED,
    FAILED
}

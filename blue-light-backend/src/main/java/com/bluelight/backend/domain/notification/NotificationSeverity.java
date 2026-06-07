package com.bluelight.backend.domain.notification;

/**
 * 알림 중요도 — Quiet Hours 우회·옵트아웃 가능 여부·SECURITY 잠금 정책의 기준.
 *
 * <p>스펙: {@code doc/Project Analysis/notification-template-manager-spec.md} §3.2(H-S3 SECURITY 잠금).
 * 카탈로그 범례: {@code doc/Project Analysis/notification-catalog.md} §0.</p>
 */
public enum NotificationSeverity {
    /** ★ — 법적·재무·보안 필수. 옵트아웃 불가, Quiet Hours 예외. */
    CRITICAL,
    /** ● — 여정 진행상 알아야 함. 카테고리별 옵트아웃 가능. */
    IMPORTANT,
    /** ○ — 참고·안심·다이제스트. */
    INFORMATIONAL,
    /** M — 명시 opt-in만, Spam Control Act §ADV 표기 필수. */
    MARKETING
}

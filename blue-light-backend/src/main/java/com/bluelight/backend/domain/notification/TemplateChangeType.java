package com.bluelight.backend.domain.notification;

/**
 * 템플릿 변경 이력의 변경 유형 — 감사·롤백 진입점 구분.
 *
 * <p>스펙: {@code doc/Project Analysis/notification-template-manager-spec.md} §5.3, §9.</p>
 */
public enum TemplateChangeType {
    /** 최초 생성 — before_snapshot 은 빈 객체. */
    CREATE,
    /** Draft approve 에 의한 본 테이블 반영. */
    PUBLISH,
    /** enabled=false → true. */
    ENABLE,
    /** enabled=true → false. SECURITY/PAYMENT 는 SYSTEM_ADMIN 만, 사유 필수. */
    DISABLE,
    /** History 의 임의 시점으로 복원 → 새 publish. */
    ROLLBACK
}

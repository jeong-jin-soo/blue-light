package com.bluelight.backend.domain.notification;

/**
 * Draft 리뷰 상태 — D-1 결정에 따른 2-step publish 워크플로 상태머신.
 *
 * <pre>
 *   PENDING  → APPROVED  (SYSTEM_ADMIN approve → 본 테이블 commit + history insert)
 *   PENDING  → REJECTED  (SYSTEM_ADMIN reject with note)
 *   PENDING  → WITHDRAWN (작성자 본인 회수)
 * </pre>
 *
 * <p>스펙: {@code doc/Project Analysis/notification-template-manager-spec.md} §9.</p>
 */
public enum TemplateDraftStatus {
    /** NM 이 submit 한 직후. SA 리뷰 대기. */
    PENDING,
    /** SA 가 승인하여 본 테이블에 반영 완료. */
    APPROVED,
    /** SA 가 거절. review_note 필수. NM 이 재편집 후 resubmit 가능. */
    REJECTED,
    /** 작성자 본인 회수. */
    WITHDRAWN
}

package com.bluelight.backend.domain.notification;

/**
 * Outbox row 의 발생 출처 — 운영 발송과 admin 테스트 발송을 격리하기 위함.
 *
 * <p>스펙: {@code doc/Project Analysis/notification-template-manager-spec.md} §5.5, §6(test-send).
 * {@code ADMIN_TEST}는 사용자 인박스 unread_count 에서 제외되며, 멱등키 prefix 가 {@code test:}로 분리된다.</p>
 */
public enum NotificationSource {
    /** 일반 비즈니스 트랜잭션에서 발화된 운영 발송. 기본값. */
    PRODUCTION,
    /** Admin 이 템플릿 편집 중 본인에게 보낸 테스트 발송. */
    ADMIN_TEST
}

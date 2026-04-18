package com.bluelight.backend.domain.notification;

/**
 * 알림 유형
 *
 * VARCHAR 기반 저장이므로 enum 값 추가는 기존 데이터와 호환된다.
 */
public enum NotificationType {
    PAYMENT_CONFIRMED,

    // Phase 3 PR#1 — LEW 서류 요청 워크플로 인앱 알림
    DOCUMENT_REQUEST_CREATED,
    DOCUMENT_REQUEST_FULFILLED,
    DOCUMENT_REQUEST_APPROVED,
    DOCUMENT_REQUEST_REJECTED
}

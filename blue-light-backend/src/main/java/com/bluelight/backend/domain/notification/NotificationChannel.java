package com.bluelight.backend.domain.notification;

/**
 * 알림 전송 채널.
 *
 * <p>{@code notification_preferences.channel}, {@code notification_templates.channel},
 * {@code notification_outbox.channel} 컬럼의 enum 값과 일대일 매핑된다.
 * VARCHAR 저장이므로 enum 값 추가는 기존 데이터와 호환된다.</p>
 */
public enum NotificationChannel {
    IN_APP,
    EMAIL,
    /**
     * 단문 메시지 — 160자 GSM-7 segment 단위. PR-T2 lint L2 진입점.
     * 채널 어댑터는 미구현 (Phase 1+ 도입 예정). 본 enum 값은 템플릿 카탈로그 정의를 위한 선행 추가.
     */
    SMS,
    WHATSAPP
}

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
    WHATSAPP
}

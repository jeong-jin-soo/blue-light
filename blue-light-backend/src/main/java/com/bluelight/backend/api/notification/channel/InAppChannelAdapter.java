package com.bluelight.backend.api.notification.channel;

import com.bluelight.backend.api.notification.NotificationService;
import com.bluelight.backend.api.notification.template.NotificationTemplateRegistry;
import com.bluelight.backend.api.notification.template.NotificationTemplateRegistry.TemplateNotFoundException;
import com.bluelight.backend.api.notification.template.RenderedMessage;
import com.bluelight.backend.domain.notification.Notification;
import com.bluelight.backend.domain.notification.NotificationChannel;
import com.bluelight.backend.domain.notification.NotificationOutbox;
import com.bluelight.backend.domain.notification.NotificationType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * 인앱 알림 채널 어댑터 (PR-0C).
 *
 * <p>{@link NotificationOutboxDispatcher} 가 호출하며, 기존 {@link NotificationService#createNotification}
 * 에 위임한다. 도메인 입장에서 행위 변경 없음 (NotificationService 가 그대로 DB 에 row 를 INSERT).</p>
 *
 * <h2>변환 흐름</h2>
 * <ol>
 *   <li>outbox row 의 {@code payload_json} → {@code Map<String,String>} 역직렬화.</li>
 *   <li>{@link NotificationTemplateRegistry} 로 (templateCode, IN_APP, locale) 렌더.</li>
 *   <li>{@code eventType} (String) → {@link NotificationType} enum 변환 — 매핑 실패 시 영구 실패.</li>
 *   <li>{@code NotificationService.createNotification} 호출 — REQUIRES_NEW 트랜잭션이라 본 어댑터의
 *       호출 트랜잭션과 분리된다.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InAppChannelAdapter implements NotificationChannelAdapter {

    private static final TypeReference<Map<String, String>> PAYLOAD_TYPE = new TypeReference<>() {};

    private final NotificationService notificationService;
    private final NotificationTemplateRegistry templateRegistry;
    private final ObjectMapper objectMapper;

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.IN_APP;
    }

    @Override
    public SendResult send(NotificationOutbox row) {
        // 1) payload 역직렬화
        Map<String, String> payload;
        try {
            payload = row.getPayloadJson() == null || row.getPayloadJson().isBlank()
                    ? Map.of()
                    : objectMapper.readValue(row.getPayloadJson(), PAYLOAD_TYPE);
        } catch (IOException e) {
            log.error("In-app payload deserialization failed: outboxSeq={}, error={}",
                    row.getOutboxSeq(), e.getMessage());
            return SendResult.permanentFailure("PAYLOAD_DESERIALIZE", e.getMessage());
        }

        // 2) eventType → NotificationType
        NotificationType type;
        try {
            type = NotificationType.valueOf(row.getEventType());
        } catch (IllegalArgumentException e) {
            log.error("Unknown NotificationType for in-app channel: eventType={}, outboxSeq={}",
                    row.getEventType(), row.getOutboxSeq());
            return SendResult.permanentFailure("UNKNOWN_EVENT_TYPE", row.getEventType());
        }

        // 3) 템플릿 렌더 (subject 가 인앱 title 로 매핑됨)
        RenderedMessage rendered;
        try {
            rendered = templateRegistry.render(row.getTemplateCode(), NotificationChannel.IN_APP, row.getLocale(), payload);
        } catch (TemplateNotFoundException e) {
            log.warn("In-app template not found: {}, outboxSeq={}", e.getMessage(), row.getOutboxSeq());
            return SendResult.permanentFailure("TEMPLATE_NOT_FOUND", e.getMessage());
        }

        // 4) NotificationService 위임 (자체 REQUIRES_NEW)
        try {
            Notification created = notificationService.createNotification(
                    row.getUserSeq(),
                    type,
                    safeTitle(rendered, type),
                    rendered.body(),
                    row.getReferenceType(),
                    row.getReferenceId());
            return SendResult.success(String.valueOf(created.getNotificationSeq()));
        } catch (RuntimeException e) {
            log.warn("In-app notification create failed (treated as retryable): outboxSeq={}, error={}",
                    row.getOutboxSeq(), e.getMessage());
            return SendResult.retryableFailure("INAPP_CREATE_FAILED", e.getMessage());
        }
    }

    /** 인앱 title 은 template.subject 가 우선, 없으면 eventType 이름 폴백 (Notification.title NOT NULL 보장). */
    private static String safeTitle(RenderedMessage rendered, NotificationType type) {
        if (rendered.subject() != null && !rendered.subject().isBlank()) {
            return rendered.subject();
        }
        return type.name();
    }
}

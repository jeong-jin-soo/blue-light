package com.bluelight.backend.api.notification.channel;

import com.bluelight.backend.api.notification.NotificationService;
import com.bluelight.backend.api.notification.channel.NotificationChannelAdapter.SendResult;
import com.bluelight.backend.api.notification.template.NotificationTemplateRegistry;
import com.bluelight.backend.api.notification.template.NotificationTemplateRegistry.TemplateNotFoundException;
import com.bluelight.backend.api.notification.template.RenderedMessage;
import com.bluelight.backend.domain.notification.Notification;
import com.bluelight.backend.domain.notification.NotificationChannel;
import com.bluelight.backend.domain.notification.NotificationOutbox;
import com.bluelight.backend.domain.notification.NotificationType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * InAppChannelAdapter 단위 테스트 (PR-0C).
 *
 * <p>payload 역직렬화 / eventType 검증 / 템플릿 lookup / NotificationService 위임의 4단계 +
 * 각 단계 실패 분기를 검증한다.</p>
 */
@DisplayName("InAppChannelAdapter - PR-0C")
class InAppChannelAdapterTest {

    private NotificationService notificationService;
    private NotificationTemplateRegistry templateRegistry;
    private ObjectMapper objectMapper;
    private InAppChannelAdapter adapter;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        templateRegistry = mock(NotificationTemplateRegistry.class);
        objectMapper = new ObjectMapper();
        adapter = new InAppChannelAdapter(notificationService, templateRegistry, objectMapper);
    }

    @Test
    @DisplayName("channel() - IN_APP 반환")
    void channel_returnsInApp() {
        assertThat(adapter.channel()).isEqualTo(NotificationChannel.IN_APP);
    }

    @Test
    @DisplayName("정상 흐름 - 템플릿 렌더 + NotificationService.createNotification 호출 → success")
    void send_happyPath() {
        NotificationOutbox row = outbox("PAYMENT_CONFIRMED", "{\"amount\":\"185\"}");
        when(templateRegistry.render(eq("T"), eq(NotificationChannel.IN_APP), eq("en"), any()))
                .thenReturn(new RenderedMessage("Payment received", "Your payment of S$185 is confirmed.", null));
        Notification created = mock(Notification.class);
        when(created.getNotificationSeq()).thenReturn(42L);
        when(notificationService.createNotification(eq(1001L), eq(NotificationType.PAYMENT_CONFIRMED),
                eq("Payment received"), eq("Your payment of S$185 is confirmed."),
                eq("APPLICATION"), eq(7L)))
                .thenReturn(created);

        SendResult result = adapter.send(row);

        assertThat(result.success()).isTrue();
        assertThat(result.providerMessageId()).isEqualTo("42");
        verify(notificationService).createNotification(anyLong(), any(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("payload JSON 깨짐 - permanentFailure PAYLOAD_DESERIALIZE")
    void send_invalidPayloadJson() {
        NotificationOutbox row = outbox("PAYMENT_CONFIRMED", "not-a-json");

        SendResult result = adapter.send(row);

        assertThat(result.success()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(result.errorCode()).isEqualTo("PAYLOAD_DESERIALIZE");
        verify(notificationService, never()).createNotification(anyLong(), any(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("payload null/empty - 빈 Map 으로 처리 (정상 진행)")
    void send_nullPayload_treatedAsEmpty() {
        NotificationOutbox row = outbox("PAYMENT_CONFIRMED", "");
        when(templateRegistry.render(any(), any(), any(), any()))
                .thenReturn(new RenderedMessage("Hi", "Body", null));
        Notification created = mock(Notification.class);
        when(created.getNotificationSeq()).thenReturn(1L);
        when(notificationService.createNotification(anyLong(), any(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(created);

        SendResult result = adapter.send(row);

        assertThat(result.success()).isTrue();
    }

    @Test
    @DisplayName("알 수 없는 eventType - permanentFailure UNKNOWN_EVENT_TYPE")
    void send_unknownEventType() {
        NotificationOutbox row = outbox("TOTALLY_FAKE_EVENT", "{}");

        SendResult result = adapter.send(row);

        assertThat(result.success()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(result.errorCode()).isEqualTo("UNKNOWN_EVENT_TYPE");
    }

    @Test
    @DisplayName("템플릿 없음 - permanentFailure TEMPLATE_NOT_FOUND")
    void send_templateNotFound() {
        NotificationOutbox row = outbox("PAYMENT_CONFIRMED", "{}");
        when(templateRegistry.render(any(), any(), any(), any()))
                .thenThrow(new TemplateNotFoundException("T", NotificationChannel.IN_APP, "en"));

        SendResult result = adapter.send(row);

        assertThat(result.success()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(result.errorCode()).isEqualTo("TEMPLATE_NOT_FOUND");
    }

    @Test
    @DisplayName("NotificationService 예외 - retryableFailure INAPP_CREATE_FAILED")
    void send_notificationServiceThrows() {
        NotificationOutbox row = outbox("PAYMENT_CONFIRMED", "{}");
        when(templateRegistry.render(any(), any(), any(), any()))
                .thenReturn(new RenderedMessage("Hi", "Body", null));
        when(notificationService.createNotification(anyLong(), any(), anyString(), anyString(), anyString(), anyLong()))
                .thenThrow(new RuntimeException("DB down"));

        SendResult result = adapter.send(row);

        assertThat(result.success()).isFalse();
        assertThat(result.retryable()).isTrue();
        assertThat(result.errorCode()).isEqualTo("INAPP_CREATE_FAILED");
    }

    @Test
    @DisplayName("template.subject 없을 때 - title fallback = eventType 이름")
    void send_titleFallbackToEventTypeName() {
        NotificationOutbox row = outbox("PAYMENT_CONFIRMED", "{}");
        when(templateRegistry.render(any(), any(), any(), any()))
                .thenReturn(new RenderedMessage(null, "Body only", null)); // subject null
        Notification created = mock(Notification.class);
        when(created.getNotificationSeq()).thenReturn(1L);
        when(notificationService.createNotification(anyLong(), any(), eq("PAYMENT_CONFIRMED"), eq("Body only"),
                anyString(), anyLong())).thenReturn(created);

        SendResult result = adapter.send(row);

        assertThat(result.success()).isTrue();
        verify(notificationService).createNotification(anyLong(), eq(NotificationType.PAYMENT_CONFIRMED),
                eq("PAYMENT_CONFIRMED"), eq("Body only"), anyString(), anyLong());
    }

    // ===== helpers =====

    private static NotificationOutbox outbox(String eventType, String payloadJson) {
        NotificationOutbox row = NotificationOutbox.builder()
                .idempotencyKey("K-" + eventType)
                .userSeq(1001L)
                .channel(NotificationChannel.IN_APP)
                .eventType(eventType)
                .templateCode("T")
                .locale("en")
                .payloadJson(payloadJson)
                .referenceType("APPLICATION")
                .referenceId(7L)
                .build();
        ReflectionTestUtils.setField(row, "outboxSeq", 500L);
        return row;
    }
}

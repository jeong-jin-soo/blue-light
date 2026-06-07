package com.bluelight.backend.api.notification.channel.whatsapp;

import com.bluelight.backend.api.notification.channel.NotificationChannelAdapter.SendResult;
import com.bluelight.backend.api.notification.channel.whatsapp.WhatsappClient.SendTemplateRequest;
import com.bluelight.backend.api.notification.template.NotificationTemplateRegistry;
import com.bluelight.backend.domain.notification.NotificationChannel;
import com.bluelight.backend.domain.notification.NotificationOutbox;
import com.bluelight.backend.domain.notification.NotificationTemplate;
import com.bluelight.backend.domain.notification.whatsapp.WhatsappMessageLog;
import com.bluelight.backend.domain.notification.whatsapp.WhatsappMessageLogRepository;
import com.bluelight.backend.domain.notification.whatsapp.WhatsappProvider;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WhatsappChannelAdapter 단위 테스트 (PR-1A).
 *
 * <p>수신자 가드 / payload / 템플릿 / provider_template_name / variables 위치 매핑 /
 * WhatsappClient 호출 결과별 SendResult 매핑 검증.</p>
 */
@DisplayName("WhatsappChannelAdapter - PR-1A")
class WhatsappChannelAdapterTest {

    private WhatsappClient whatsappClient;
    private NotificationTemplateRegistry templateRegistry;
    private UserRepository userRepository;
    private WhatsappMessageLogRepository messageLogRepository;
    private ObjectMapper objectMapper;
    private WhatsappChannelAdapter adapter;

    @BeforeEach
    void setUp() {
        whatsappClient = mock(WhatsappClient.class);
        templateRegistry = mock(NotificationTemplateRegistry.class);
        userRepository = mock(UserRepository.class);
        messageLogRepository = mock(WhatsappMessageLogRepository.class);
        objectMapper = new ObjectMapper();
        adapter = new WhatsappChannelAdapter(whatsappClient, templateRegistry, userRepository,
                messageLogRepository, objectMapper);
        when(whatsappClient.provider()).thenReturn(WhatsappProvider.MOCK);
    }

    @Test
    @DisplayName("channel() - WHATSAPP 반환")
    void channel_returnsWhatsapp() {
        assertThat(adapter.channel()).isEqualTo(NotificationChannel.WHATSAPP);
    }

    @Test
    @DisplayName("정상 흐름 - WhatsappClient 호출 + MessageLog 저장 + success")
    void send_happyPath() {
        when(userRepository.findById(1001L)).thenReturn(Optional.of(reachableUser()));
        when(templateRegistry.findActive(any(), any(), any()))
                .thenReturn(Optional.of(template("payment_confirmed_applicant",
                        "[\"applicantName\",\"amount\"]")));
        when(whatsappClient.sendTemplate(any()))
                .thenReturn(WhatsappClient.SendResult.queued("wamid-xyz"));

        SendResult result = adapter.send(outbox("{\"applicantName\":\"Alice\",\"amount\":\"185.00\"}"));

        assertThat(result.success()).isTrue();
        assertThat(result.providerMessageId()).isEqualTo("wamid-xyz");

        ArgumentCaptor<SendTemplateRequest> reqCap = ArgumentCaptor.forClass(SendTemplateRequest.class);
        verify(whatsappClient).sendTemplate(reqCap.capture());
        assertThat(reqCap.getValue().toE164()).isEqualTo("+6591234567");
        assertThat(reqCap.getValue().providerTemplateName()).isEqualTo("payment_confirmed_applicant");
        assertThat(reqCap.getValue().variables()).containsExactly("Alice", "185.00");

        ArgumentCaptor<WhatsappMessageLog> logCap = ArgumentCaptor.forClass(WhatsappMessageLog.class);
        verify(messageLogRepository).save(logCap.capture());
        assertThat(logCap.getValue().getProviderMessageId()).isEqualTo("wamid-xyz");
    }

    @Test
    @DisplayName("user 없음 - permanentFailure USER_NOT_FOUND, 클라이언트 호출 안 됨")
    void send_userNotFound() {
        when(userRepository.findById(1001L)).thenReturn(Optional.empty());

        SendResult result = adapter.send(outbox("{}"));

        assertThat(result.success()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(result.errorCode()).isEqualTo("USER_NOT_FOUND");
        verify(whatsappClient, never()).sendTemplate(any());
        verify(messageLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("user reachable=false - permanentFailure USER_NOT_REACHABLE")
    void send_userNotReachable() {
        User u = User.builder().email("u@t.sg").password("x").firstName("U").lastName("L").build();
        ReflectionTestUtils.setField(u, "userSeq", 1001L);
        // 옵트인 안 함 → isWhatsappReachable=false
        when(userRepository.findById(1001L)).thenReturn(Optional.of(u));

        SendResult result = adapter.send(outbox("{}"));

        assertThat(result.success()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(result.errorCode()).isEqualTo("USER_NOT_REACHABLE");
    }

    @Test
    @DisplayName("payload JSON 깨짐 - permanentFailure PAYLOAD_DESERIALIZE")
    void send_invalidPayload() {
        when(userRepository.findById(1001L)).thenReturn(Optional.of(reachableUser()));

        SendResult result = adapter.send(outbox("not-json"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("PAYLOAD_DESERIALIZE");
    }

    @Test
    @DisplayName("템플릿 없음 - permanentFailure TEMPLATE_NOT_FOUND")
    void send_templateNotFound() {
        when(userRepository.findById(1001L)).thenReturn(Optional.of(reachableUser()));
        when(templateRegistry.findActive(any(), any(), any())).thenReturn(Optional.empty());

        SendResult result = adapter.send(outbox("{}"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("TEMPLATE_NOT_FOUND");
    }

    @Test
    @DisplayName("provider_template_name 비어있음 - permanentFailure NO_PROVIDER_TEMPLATE")
    void send_noProviderTemplateName() {
        when(userRepository.findById(1001L)).thenReturn(Optional.of(reachableUser()));
        when(templateRegistry.findActive(any(), any(), any()))
                .thenReturn(Optional.of(template(null, "[]")));

        SendResult result = adapter.send(outbox("{}"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("NO_PROVIDER_TEMPLATE");
    }

    @Test
    @DisplayName("variables_json 깨짐 - permanentFailure VARIABLES_MALFORMED")
    void send_variablesJsonMalformed() {
        when(userRepository.findById(1001L)).thenReturn(Optional.of(reachableUser()));
        when(templateRegistry.findActive(any(), any(), any()))
                .thenReturn(Optional.of(template("tpl", "not-an-array")));

        SendResult result = adapter.send(outbox("{}"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("VARIABLES_MALFORMED");
    }

    @Test
    @DisplayName("variables_json 의 키가 payload 에 없으면 - 빈 문자열로 채움")
    void send_missingPayloadKeysBecomeEmpty() {
        when(userRepository.findById(1001L)).thenReturn(Optional.of(reachableUser()));
        when(templateRegistry.findActive(any(), any(), any()))
                .thenReturn(Optional.of(template("tpl", "[\"a\",\"b\",\"c\"]")));
        when(whatsappClient.sendTemplate(any()))
                .thenReturn(WhatsappClient.SendResult.queued("wamid"));

        adapter.send(outbox("{\"a\":\"AAA\"}"));

        ArgumentCaptor<SendTemplateRequest> reqCap = ArgumentCaptor.forClass(SendTemplateRequest.class);
        verify(whatsappClient).sendTemplate(reqCap.capture());
        assertThat(reqCap.getValue().variables()).containsExactly("AAA", "", "");
    }

    @Test
    @DisplayName("provider 가 REJECTED - permanentFailure PROVIDER_REJECTED + log FAILED")
    void send_providerRejected() {
        when(userRepository.findById(1001L)).thenReturn(Optional.of(reachableUser()));
        when(templateRegistry.findActive(any(), any(), any()))
                .thenReturn(Optional.of(template("tpl", "[]")));
        when(whatsappClient.sendTemplate(any()))
                .thenReturn(WhatsappClient.SendResult.rejected("131000", "template not approved"));

        SendResult result = adapter.send(outbox("{}"));

        assertThat(result.success()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(result.errorCode()).isEqualTo("PROVIDER_REJECTED");

        ArgumentCaptor<WhatsappMessageLog> logCap = ArgumentCaptor.forClass(WhatsappMessageLog.class);
        verify(messageLogRepository).save(logCap.capture());
        assertThat(logCap.getValue().getErrorCode()).isEqualTo("131000");
    }

    @Test
    @DisplayName("provider 가 ERROR (일시 실패) - retryableFailure PROVIDER_ERROR")
    void send_providerError() {
        when(userRepository.findById(1001L)).thenReturn(Optional.of(reachableUser()));
        when(templateRegistry.findActive(any(), any(), any()))
                .thenReturn(Optional.of(template("tpl", "[]")));
        when(whatsappClient.sendTemplate(any()))
                .thenReturn(WhatsappClient.SendResult.error("TIMEOUT", "graph timeout"));

        SendResult result = adapter.send(outbox("{}"));

        assertThat(result.success()).isFalse();
        assertThat(result.retryable()).isTrue();
        assertThat(result.errorCode()).isEqualTo("PROVIDER_ERROR");
    }

    @Test
    @DisplayName("WhatsappClient 가 예외 던짐 - retryableFailure PROVIDER_ERROR + log FAILED")
    void send_clientThrowsException() {
        when(userRepository.findById(1001L)).thenReturn(Optional.of(reachableUser()));
        when(templateRegistry.findActive(any(), any(), any()))
                .thenReturn(Optional.of(template("tpl", "[]")));
        when(whatsappClient.sendTemplate(any()))
                .thenThrow(new RuntimeException("boom"));

        SendResult result = adapter.send(outbox("{}"));

        assertThat(result.success()).isFalse();
        assertThat(result.retryable()).isTrue();
        assertThat(result.errorCode()).isEqualTo("PROVIDER_ERROR");
    }

    // ===== helpers =====

    private static User reachableUser() {
        User u = User.builder()
                .email("u@test.sg").password("x").firstName("U").lastName("L")
                .build();
        ReflectionTestUtils.setField(u, "userSeq", 1001L);
        u.verifyPhone("+6591234567", LocalDateTime.now());
        u.optInWhatsapp(LocalDateTime.now());
        return u;
    }

    private static NotificationTemplate template(String providerName, String variablesJson) {
        return NotificationTemplate.builder()
                .templateCode("PAYMENT_CONFIRMED_APPLICANT")
                .channel(NotificationChannel.WHATSAPP)
                .locale("en")
                .providerTemplateName(providerName)
                .subject(null)
                .bodyText("Preview body")
                .variablesJson(variablesJson)
                .enabled(true)
                .build();
    }

    private static NotificationOutbox outbox(String payloadJson) {
        NotificationOutbox row = NotificationOutbox.builder()
                .idempotencyKey("PAYMENT_CONFIRMED:APPLICATION:42:1001:WHATSAPP")
                .userSeq(1001L)
                .channel(NotificationChannel.WHATSAPP)
                .eventType("PAYMENT_CONFIRMED")
                .templateCode("PAYMENT_CONFIRMED_APPLICANT")
                .locale("en")
                .payloadJson(payloadJson)
                .referenceType("APPLICATION")
                .referenceId(42L)
                .build();
        ReflectionTestUtils.setField(row, "outboxSeq", 999L);
        return row;
    }
}

package com.bluelight.backend.api.notification.channel;

import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.api.notification.channel.NotificationChannelAdapter.SendResult;
import com.bluelight.backend.api.notification.template.NotificationTemplateRegistry;
import com.bluelight.backend.api.notification.template.NotificationTemplateRegistry.TemplateNotFoundException;
import com.bluelight.backend.api.notification.template.RenderedMessage;
import com.bluelight.backend.domain.notification.NotificationChannel;
import com.bluelight.backend.domain.notification.NotificationOutbox;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EmailChannelAdapter 단위 테스트 (PR-0C).
 *
 * <p>수신자 가드 / payload / template / EmailService 위임 각 단계 + 실패 분기 검증.</p>
 */
@DisplayName("EmailChannelAdapter - PR-0C")
class EmailChannelAdapterTest {

    private EmailService emailService;
    private NotificationTemplateRegistry templateRegistry;
    private UserRepository userRepository;
    private ObjectMapper objectMapper;
    private EmailChannelAdapter adapter;

    @BeforeEach
    void setUp() {
        emailService = mock(EmailService.class);
        templateRegistry = mock(NotificationTemplateRegistry.class);
        userRepository = mock(UserRepository.class);
        objectMapper = new ObjectMapper();
        adapter = new EmailChannelAdapter(emailService, templateRegistry, userRepository, objectMapper);
    }

    @Test
    @DisplayName("channel() - EMAIL 반환")
    void channel_returnsEmail() {
        assertThat(adapter.channel()).isEqualTo(NotificationChannel.EMAIL);
    }

    @Test
    @DisplayName("운영 프로필 - 제목에 코드 prefix 없이 sendGenericEmail 위임 + success")
    void send_happyPath_prodNoPrefix() {
        ReflectionTestUtils.setField(adapter, "activeProfiles", "prod");
        NotificationOutbox row = outbox("{\"amount\":\"185\"}");
        when(userRepository.findById(1001L)).thenReturn(Optional.of(userWithEmail("ringo@test.sg")));
        when(templateRegistry.render(eq("T"), eq(NotificationChannel.EMAIL), eq("en"), any()))
                .thenReturn(new RenderedMessage("Payment", "<p>Confirmed</p>", null));

        SendResult result = adapter.send(row);

        assertThat(result.success()).isTrue();
        assertThat(result.providerMessageId()).isEqualTo("outbox-500");

        ArgumentCaptor<String> toCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subjCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bodyCap = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendGenericEmail(toCap.capture(), subjCap.capture(), bodyCap.capture());
        assertThat(toCap.getValue()).isEqualTo("ringo@test.sg");
        assertThat(subjCap.getValue()).isEqualTo("Payment");
        assertThat(bodyCap.getValue()).isEqualTo("<p>Confirmed</p>");
    }

    @Test
    @DisplayName("개발서버(비-prod 프로필) - 제목 앞에 메일 코드 prefix")
    void send_devServer_prefixesCode() {
        ReflectionTestUtils.setField(adapter, "activeProfiles", "default");
        NotificationOutbox row = outbox("{\"amount\":\"185\"}");
        when(userRepository.findById(1001L)).thenReturn(Optional.of(userWithEmail("ringo@test.sg")));
        when(templateRegistry.render(eq("A-17"), eq(NotificationChannel.EMAIL), eq("en"), any()))
                .thenReturn(new RenderedMessage("Payment Requested", "<p>Pay</p>", null));
        ReflectionTestUtils.setField(row, "templateCode", "A-17");

        SendResult result = adapter.send(row);

        assertThat(result.success()).isTrue();
        ArgumentCaptor<String> subjCap = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendGenericEmail(anyString(), subjCap.capture(), anyString());
        assertThat(subjCap.getValue()).isEqualTo("[A-17] Payment Requested");
    }

    @Test
    @DisplayName("수신자 user 없음 - permanentFailure USER_NOT_FOUND")
    void send_userNotFound() {
        when(userRepository.findById(1001L)).thenReturn(Optional.empty());

        SendResult result = adapter.send(outbox("{}"));

        assertThat(result.success()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(result.errorCode()).isEqualTo("USER_NOT_FOUND");
        verify(emailService, never()).sendGenericEmail(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("user DELETED 상태 - permanentFailure USER_INACTIVE")
    void send_userDeleted() {
        User user = userWithEmail("ringo@test.sg");
        user.softDelete(); // status=DELETED
        when(userRepository.findById(1001L)).thenReturn(Optional.of(user));

        SendResult result = adapter.send(outbox("{}"));

        assertThat(result.success()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(result.errorCode()).isEqualTo("USER_INACTIVE");
    }

    @Test
    @DisplayName("user email blank - permanentFailure NO_EMAIL")
    void send_blankEmail() {
        User user = userWithEmail("ringo@test.sg");
        ReflectionTestUtils.setField(user, "email", "");
        when(userRepository.findById(1001L)).thenReturn(Optional.of(user));

        SendResult result = adapter.send(outbox("{}"));

        assertThat(result.success()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(result.errorCode()).isEqualTo("NO_EMAIL");
    }

    @Test
    @DisplayName("user 익명화 (deleted-...@) - permanentFailure NO_EMAIL")
    void send_anonymizedEmail() {
        User user = userWithEmail("deleted-123@deleted.licensekaki.sg");
        when(userRepository.findById(1001L)).thenReturn(Optional.of(user));

        SendResult result = adapter.send(outbox("{}"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("NO_EMAIL");
    }

    @Test
    @DisplayName("payload JSON 깨짐 - permanentFailure PAYLOAD_DESERIALIZE")
    void send_invalidPayloadJson() {
        when(userRepository.findById(1001L)).thenReturn(Optional.of(userWithEmail("r@t.sg")));

        SendResult result = adapter.send(outbox("not-json"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("PAYLOAD_DESERIALIZE");
    }

    @Test
    @DisplayName("템플릿 없음 - permanentFailure TEMPLATE_NOT_FOUND")
    void send_templateNotFound() {
        when(userRepository.findById(1001L)).thenReturn(Optional.of(userWithEmail("r@t.sg")));
        when(templateRegistry.render(any(), any(), any(), any()))
                .thenThrow(new TemplateNotFoundException("T", NotificationChannel.EMAIL, "en"));

        SendResult result = adapter.send(outbox("{}"));

        assertThat(result.success()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(result.errorCode()).isEqualTo("TEMPLATE_NOT_FOUND");
    }

    @Test
    @DisplayName("SMTP 발송 예외 - retryableFailure SMTP_FAILED")
    void send_smtpThrows() {
        when(userRepository.findById(1001L)).thenReturn(Optional.of(userWithEmail("r@t.sg")));
        when(templateRegistry.render(any(), any(), any(), any()))
                .thenReturn(new RenderedMessage("S", "B", null));
        doThrow(new RuntimeException("smtp down")).when(emailService).sendGenericEmail(anyString(), anyString(), anyString());

        SendResult result = adapter.send(outbox("{}"));

        assertThat(result.success()).isFalse();
        assertThat(result.retryable()).isTrue();
        assertThat(result.errorCode()).isEqualTo("SMTP_FAILED");
    }

    // ===== helpers =====

    private static User userWithEmail(String email) {
        User user = User.builder()
                .email(email)
                .password("x")
                .firstName("T")
                .lastName("U")
                .build();
        ReflectionTestUtils.setField(user, "userSeq", 1001L);
        return user;
    }

    private static NotificationOutbox outbox(String payloadJson) {
        NotificationOutbox row = NotificationOutbox.builder()
                .idempotencyKey("K")
                .userSeq(1001L)
                .channel(NotificationChannel.EMAIL)
                .eventType("PAYMENT_CONFIRMED")
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

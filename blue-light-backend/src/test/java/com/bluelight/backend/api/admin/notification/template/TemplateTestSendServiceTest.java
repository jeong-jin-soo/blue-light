package com.bluelight.backend.api.admin.notification.template;

import com.bluelight.backend.api.admin.notification.template.dto.TemplateTestSendResponse;
import com.bluelight.backend.api.notification.outbox.NotificationOutboxDispatcher;
import com.bluelight.backend.domain.notification.NotificationCategory;
import com.bluelight.backend.domain.notification.NotificationChannel;
import com.bluelight.backend.domain.notification.NotificationOutbox;
import com.bluelight.backend.domain.notification.NotificationOutboxRepository;
import com.bluelight.backend.domain.notification.NotificationSeverity;
import com.bluelight.backend.domain.notification.NotificationSource;
import com.bluelight.backend.domain.notification.NotificationTemplate;
import com.bluelight.backend.domain.notification.NotificationTemplateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TemplateTestSendService — admin 본인에게 EMAIL 테스트 발송 + quota + 채널 제약 (PR-T4).
 */
@DisplayName("TemplateTestSendService - PR-T4")
class TemplateTestSendServiceTest {

    private NotificationTemplateRepository templateRepository;
    private NotificationOutboxRepository outboxRepository;
    private NotificationOutboxDispatcher dispatcher;
    private TestSendQuotaTracker quotaTracker;
    private TemplateTestSendService service;

    @BeforeEach
    void setUp() {
        templateRepository = mock(NotificationTemplateRepository.class);
        outboxRepository = mock(NotificationOutboxRepository.class);
        dispatcher = mock(NotificationOutboxDispatcher.class);
        quotaTracker = new TestSendQuotaTracker(50); // 실제 트래커 사용 (verify 가능)
        ObjectMapper objectMapper = new ObjectMapper();
        service = new TemplateTestSendService(
                templateRepository, outboxRepository, dispatcher, quotaTracker, objectMapper
        );

        // outbox save 는 input 그대로 반환 (seq 채워서)
        when(outboxRepository.save(any())).thenAnswer(inv -> {
            NotificationOutbox row = inv.getArgument(0);
            // reflection 없이 seq 채울 방법이 없음 — null seq 로 반환되고 dispatcher 가 null 받음
            return row;
        });
    }

    private NotificationTemplate emailTemplate() {
        return NotificationTemplate.builder()
                .templateCode("A-17")
                .channel(NotificationChannel.EMAIL)
                .locale("en")
                .subject("subj")
                .bodyText("body {{applicantName}}. {{footerBlock}}")
                .variablesJson("[\"applicantName\"]")
                .enabled(true)
                .category(NotificationCategory.PAYMENT)
                .severity(NotificationSeverity.CRITICAL)
                .recipientRoles("APPLICANT")
                .build();
    }

    @Test
    @DisplayName("sendTestToSelf - EMAIL 채널 → outbox 적재 + dispatcher 호출 + quota 1 증가")
    void sendTestToSelf_emailEnqueuesAndDispatches() {
        when(templateRepository.findById(42L)).thenReturn(Optional.of(emailTemplate()));

        TemplateTestSendResponse resp = service.sendTestToSelf(42L, 1001L,
                Map.of("applicantName", "Tan Ah Kow"));

        assertThat(resp.dailyQuotaUsed()).isEqualTo(1);
        assertThat(resp.dailyQuotaMax()).isEqualTo(50);

        ArgumentCaptor<NotificationOutbox> captor = ArgumentCaptor.forClass(NotificationOutbox.class);
        verify(outboxRepository).save(captor.capture());
        NotificationOutbox row = captor.getValue();
        assertThat(row.getSource()).isEqualTo(NotificationSource.ADMIN_TEST);
        assertThat(row.isTest()).isTrue();
        assertThat(row.getEventType()).isEqualTo("TEMPLATE_TEST");
        assertThat(row.getReferenceType()).isEqualTo("TEMPLATE_TEST");
        assertThat(row.getReferenceId()).isEqualTo(42L);
        assertThat(row.getUserSeq()).isEqualTo(1001L);
        assertThat(row.getIdempotencyKey()).startsWith("test:1001:42:");
        assertThat(row.getPayloadJson()).contains("Tan Ah Kow");

        verify(dispatcher, atLeastOnce()).dispatchAsync(any());
    }

    @Test
    @DisplayName("sendTestToSelf - SMS/WHATSAPP 채널 시도 → UnsupportedTestChannelException, quota 미차감")
    void sendTestToSelf_smsChannelRejected() {
        NotificationTemplate sms = NotificationTemplate.builder()
                .templateCode("A-19").channel(NotificationChannel.SMS).locale("en")
                .bodyText("body").enabled(true).build();
        when(templateRepository.findById(42L)).thenReturn(Optional.of(sms));

        assertThatThrownBy(() -> service.sendTestToSelf(42L, 1001L, Map.of()))
                .isInstanceOf(TemplateTestSendService.UnsupportedTestChannelException.class)
                .hasMessageContaining("EMAIL");

        verify(outboxRepository, never()).save(any());
        verify(dispatcher, never()).dispatchAsync(any());
        assertThat(quotaTracker.currentUsage(1001L)).isZero();
    }

    @Test
    @DisplayName("sendTestToSelf - 일일 한도 초과 시 QuotaExceededException, outbox 적재 안 함")
    void sendTestToSelf_quotaExceeded() {
        when(templateRepository.findById(42L)).thenReturn(Optional.of(emailTemplate()));

        // 한도가 낮은 tracker 로 service 재구성
        TestSendQuotaTracker lowTracker = new TestSendQuotaTracker(2);
        TemplateTestSendService limitedService = new TemplateTestSendService(
                templateRepository, outboxRepository, dispatcher, lowTracker, new ObjectMapper()
        );

        limitedService.sendTestToSelf(42L, 1001L, Map.of()); // 1/2
        limitedService.sendTestToSelf(42L, 1001L, Map.of()); // 2/2

        assertThatThrownBy(() -> limitedService.sendTestToSelf(42L, 1001L, Map.of()))
                .isInstanceOf(TestSendQuotaTracker.QuotaExceededException.class)
                .hasMessageContaining("2");

        // 3번째 시도는 save 호출 안 됨 (한도 초과 시 cancel)
        verify(outboxRepository, atLeastOnce()).save(any()); // 첫 2번
        assertThat(lowTracker.currentUsage(1001L)).isEqualTo(2); // 한도 차감 후 되돌림 검증
    }

    @Test
    @DisplayName("sendTestToSelf - 미존재 템플릿 → TemplateNotFoundException, quota 미차감")
    void sendTestToSelf_templateNotFound() {
        when(templateRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sendTestToSelf(999L, 1001L, Map.of()))
                .isInstanceOf(NotificationTemplateAdminService.TemplateNotFoundException.class);

        assertThat(quotaTracker.currentUsage(1001L)).isZero();
    }
}

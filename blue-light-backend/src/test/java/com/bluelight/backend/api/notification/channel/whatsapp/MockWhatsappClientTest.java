package com.bluelight.backend.api.notification.channel.whatsapp;

import com.bluelight.backend.api.notification.channel.whatsapp.WhatsappClient.ProviderStatus;
import com.bluelight.backend.api.notification.channel.whatsapp.WhatsappClient.SendResult;
import com.bluelight.backend.api.notification.channel.whatsapp.WhatsappClient.SendTemplateRequest;
import com.bluelight.backend.domain.notification.whatsapp.WhatsappProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MockWhatsappClient 단위 테스트 (PR-1A).
 *
 * <p>Mock 은 항상 QUEUED 를 반환하고 provider_message_id 는 {@code mock-{uuid}} 형식.
 * 외부 호출 없이 stdout 로그만 출력.</p>
 */
@DisplayName("MockWhatsappClient - PR-1A")
class MockWhatsappClientTest {

    private final MockWhatsappClient client = new MockWhatsappClient();

    @Test
    @DisplayName("provider() - MOCK 반환")
    void provider_returnsMock() {
        assertThat(client.provider()).isEqualTo(WhatsappProvider.MOCK);
    }

    @Test
    @DisplayName("sendTemplate - 항상 QUEUED + mock- 접두사 messageId")
    void sendTemplate_alwaysQueued() {
        SendTemplateRequest req = new SendTemplateRequest(
                "+6591234567",
                "payment_confirmed_applicant",
                "en",
                List.of("Alice", "2026-00428", "185.00"),
                "TEST:APPLICATION:1:1001:WHATSAPP");

        SendResult result = client.sendTemplate(req);

        assertThat(result.status()).isEqualTo(ProviderStatus.QUEUED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isRetryable()).isFalse();
        assertThat(result.providerMessageId()).startsWith("mock-");
        assertThat(result.errorCode()).isNull();
        assertThat(result.errorMessage()).isNull();
    }

    @Test
    @DisplayName("sendTemplate - 매번 다른 messageId (UUID)")
    void sendTemplate_messageIdIsUnique() {
        SendTemplateRequest req = new SendTemplateRequest(
                "+6591234567", "tpl", "en", List.of(), "key1");
        String id1 = client.sendTemplate(req).providerMessageId();
        String id2 = client.sendTemplate(req).providerMessageId();
        assertThat(id1).isNotEqualTo(id2);
    }
}

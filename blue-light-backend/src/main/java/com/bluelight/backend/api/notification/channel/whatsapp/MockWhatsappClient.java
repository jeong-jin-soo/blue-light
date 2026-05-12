package com.bluelight.backend.api.notification.channel.whatsapp;

import com.bluelight.backend.domain.notification.whatsapp.WhatsappProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * WhatsApp 발송 mock — 외부 호출 없이 stdout 로그만 출력 (PR-1A).
 *
 * <h2>활성화 조건</h2>
 * <ul>
 *   <li>{@code whatsapp.provider=mock} (기본값) — 명시 활성.</li>
 *   <li>속성이 비어 있어도 {@code matchIfMissing=true} 로 활성 (로컬/CI 안전 기본).</li>
 *   <li>PR-1B 의 {@code MetaCloudWhatsappClient} 는 {@code whatsapp.provider=meta} 일 때만 활성되어
 *       Mock 과 상호 배타 — {@link org.springframework.boot.autoconfigure.condition.ConditionalOnProperty}
 *       만으로 충분 (self-referential ConditionalOnMissingBean 회피).</li>
 * </ul>
 *
 * <p>로그 형식은 {@code LogOnlyEmailService} 패턴과 일관:
 * <pre>
 * ==================================================
 * [DEV] WhatsApp Send (Mock — not actually sent)
 *   To: +6591234567
 *   Template: payment_confirmed_applicant (en)
 *   Variables: [Alice, 2026-00428, 123 Orchard, 185.00]
 *   Idempotency: PAYMENT_CONFIRMED:APPLICATION:428:1001:WHATSAPP
 *   Provider Message ID: mock-{uuid}
 * ==================================================
 * </pre>
 */
@Component
@ConditionalOnProperty(name = "whatsapp.provider", havingValue = "mock", matchIfMissing = true)
@Slf4j
public class MockWhatsappClient implements WhatsappClient {

    @Override
    public WhatsappProvider provider() {
        return WhatsappProvider.MOCK;
    }

    @Override
    public SendResult sendTemplate(SendTemplateRequest request) {
        String providerMessageId = "mock-" + UUID.randomUUID();
        log.info("==================================================");
        log.info("[DEV] WhatsApp Send (Mock — not actually sent)");
        log.info("  To: {}", request.toE164());
        log.info("  Template: {} ({})", request.providerTemplateName(), request.locale());
        log.info("  Variables: {}", request.variables());
        log.info("  Idempotency: {}", request.idempotencyKey());
        log.info("  Provider Message ID: {}", providerMessageId);
        log.info("==================================================");
        return SendResult.queued(providerMessageId);
    }
}

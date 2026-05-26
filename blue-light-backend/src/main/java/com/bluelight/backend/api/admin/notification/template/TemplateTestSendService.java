package com.bluelight.backend.api.admin.notification.template;

import com.bluelight.backend.api.admin.notification.template.dto.TemplateTestSendResponse;
import com.bluelight.backend.api.notification.outbox.NotificationOutboxDispatcher;
import com.bluelight.backend.domain.notification.NotificationChannel;
import com.bluelight.backend.domain.notification.NotificationOutbox;
import com.bluelight.backend.domain.notification.NotificationOutboxRepository;
import com.bluelight.backend.domain.notification.NotificationSource;
import com.bluelight.backend.domain.notification.NotificationTemplate;
import com.bluelight.backend.domain.notification.NotificationTemplateRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Admin 테스트 발송 — 본인에게 EMAIL 만 (MVP). PR-T4.
 *
 * <p>일반 발송 경로(NotificationOrchestrator)를 우회하여 직접 outbox row 를 적재한다.
 * <ul>
 *   <li>{@code source=ADMIN_TEST}, {@code isTest=true} — 운영 인박스 카운트에서 제외 (스펙 §5.5)</li>
 *   <li>{@code idempotencyKey} prefix {@code test:} — 운영 키와 충돌 방지</li>
 *   <li>일일 한도 {@code 50} 통 (스펙 §7.4, US-04 AC)</li>
 *   <li>EMAIL 만 (SMS/WhatsApp 은 PR 후속 / IN_APP 은 preview 로 대체)</li>
 * </ul>
 *
 * <p>{@code NotificationOutboxDispatcher.dispatchAsync} 로 즉시 발송. 실패 시 retry scheduler 가
 * 재처리하지만, 테스트 발송은 admin 인지가 빠른 게 중요 → 첫 시도 결과를 outbox 상태로 확인.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TemplateTestSendService {

    private final NotificationTemplateRepository templateRepository;
    private final NotificationOutboxRepository outboxRepository;
    private final NotificationOutboxDispatcher dispatcher;
    private final TestSendQuotaTracker quotaTracker;
    private final ObjectMapper objectMapper;

    @Transactional
    public TemplateTestSendResponse sendTestToSelf(Long templateSeq,
                                                   Long adminUserSeq,
                                                   Map<String, String> payload) {
        NotificationTemplate template = templateRepository.findById(templateSeq)
                .orElseThrow(() -> new NotificationTemplateAdminService.TemplateNotFoundException(templateSeq));

        // MVP — EMAIL 만 지원. SMS/WhatsApp 은 비용·번호 검증 이슈로 보류.
        if (template.getChannel() != NotificationChannel.EMAIL) {
            throw new UnsupportedTestChannelException(template.getChannel());
        }

        int usageAfter = quotaTracker.tryConsume(adminUserSeq);

        Map<String, String> safePayload = payload != null ? payload : Map.of();
        String payloadJson = serialize(safePayload);

        String idempotencyKey = "test:" + adminUserSeq + ":" + templateSeq + ":" + Instant.now().getEpochSecond();

        NotificationOutbox row = NotificationOutbox.builder()
                .idempotencyKey(idempotencyKey)
                .userSeq(adminUserSeq)
                .channel(template.getChannel())
                .eventType("TEMPLATE_TEST")
                .templateCode(template.getTemplateCode())
                .locale(template.getLocale())
                .payloadJson(payloadJson)
                .referenceType("TEMPLATE_TEST")
                .referenceId(templateSeq)
                .source(NotificationSource.ADMIN_TEST)
                .isTest(true)
                .build();

        NotificationOutbox saved = outboxRepository.save(row);

        // 트랜잭션 커밋 후 비동기 발송. @Async 이므로 즉시 반환.
        dispatcher.dispatchAsync(saved.getOutboxSeq());

        log.info("Test send queued: adminUserSeq={}, templateSeq={}, outboxSeq={}, usage={}/{}",
                adminUserSeq, templateSeq, saved.getOutboxSeq(), usageAfter, quotaTracker.dailyMax());

        return new TemplateTestSendResponse(saved.getOutboxSeq(), usageAfter, quotaTracker.dailyMax());
    }

    private String serialize(Map<String, String> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("payload 직렬화 실패", e);
        }
    }

    public static class UnsupportedTestChannelException extends RuntimeException {
        public UnsupportedTestChannelException(NotificationChannel channel) {
            super("테스트 발송은 EMAIL 만 지원합니다 (현재 채널: " + channel + "). "
                    + "IN_APP 은 Preview 응답으로 확인하세요. SMS/WhatsApp 은 후속 PR 예정.");
        }
    }
}

package com.bluelight.backend.api.notification.channel.whatsapp;

import com.bluelight.backend.api.notification.channel.NotificationChannelAdapter;
import com.bluelight.backend.api.notification.channel.whatsapp.WhatsappClient.SendTemplateRequest;
import com.bluelight.backend.api.notification.template.NotificationTemplateRegistry;
import com.bluelight.backend.domain.notification.NotificationChannel;
import com.bluelight.backend.domain.notification.NotificationOutbox;
import com.bluelight.backend.domain.notification.NotificationTemplate;
import com.bluelight.backend.domain.notification.whatsapp.WhatsappMessageLog;
import com.bluelight.backend.domain.notification.whatsapp.WhatsappMessageLogRepository;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * WhatsApp 채널 어댑터 (PR-1A) — Outbox 디스패처가 호출.
 *
 * <h2>처리 흐름</h2>
 * <ol>
 *   <li>수신자 user 조회 + {@link User#isWhatsappReachable()} 가드 (옵트인/검증 안전망)</li>
 *   <li>payload_json → {@code Map} 역직렬화</li>
 *   <li>{@link NotificationTemplateRegistry#findActive} 로 (templateCode, WHATSAPP, locale) 매칭</li>
 *   <li>{@code provider_template_name} 검증 — WhatsApp 은 사전 승인된 템플릿명 필수</li>
 *   <li>{@code variables_json} 파싱 → payload Map 에서 위치순으로 값 추출 ({{1}}, {{2}}, ...)</li>
 *   <li>{@link WhatsappClient#sendTemplate} 호출 (Mock 또는 Meta Cloud API)</li>
 *   <li>{@link WhatsappMessageLog} 저장 (provider_message_id + 상태 = QUEUED/FAILED)</li>
 *   <li>{@link com.bluelight.backend.api.notification.channel.NotificationChannelAdapter.SendResult} 매핑 반환</li>
 * </ol>
 *
 * <h2>실패 분류</h2>
 * <ul>
 *   <li>{@code USER_NOT_FOUND / USER_NOT_REACHABLE} — 영구 실패 (옵트인/검증 안된 사용자)</li>
 *   <li>{@code PAYLOAD_DESERIALIZE / TEMPLATE_NOT_FOUND / NO_PROVIDER_TEMPLATE / VARIABLES_MALFORMED}
 *       — 영구 실패 (운영 오설정 — admin 이 템플릿 수정해야 복구)</li>
 *   <li>{@code PROVIDER_REJECTED} — 영구 실패 (Meta 가 즉시 거절)</li>
 *   <li>{@code PROVIDER_ERROR} — 재시도 가능 (네트워크/일시 장애)</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WhatsappChannelAdapter implements NotificationChannelAdapter {

    private static final TypeReference<Map<String, String>> PAYLOAD_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> VAR_KEYS_TYPE = new TypeReference<>() {};

    private final WhatsappClient whatsappClient;
    private final NotificationTemplateRegistry templateRegistry;
    private final UserRepository userRepository;
    private final WhatsappMessageLogRepository messageLogRepository;
    private final ObjectMapper objectMapper;

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.WHATSAPP;
    }

    @Override
    public SendResult send(NotificationOutbox row) {
        // 1) 수신자 가드
        Optional<User> recipientOpt = userRepository.findById(row.getUserSeq());
        if (recipientOpt.isEmpty()) {
            return SendResult.permanentFailure("USER_NOT_FOUND", "userSeq=" + row.getUserSeq());
        }
        User recipient = recipientOpt.get();
        if (!recipient.isWhatsappReachable()) {
            return SendResult.permanentFailure("USER_NOT_REACHABLE",
                    "phoneVerified/optIn/optOut/DELETED 가드 실패");
        }

        // 2) payload 역직렬화
        Map<String, String> payload;
        try {
            payload = row.getPayloadJson() == null || row.getPayloadJson().isBlank()
                    ? Map.of()
                    : objectMapper.readValue(row.getPayloadJson(), PAYLOAD_TYPE);
        } catch (IOException e) {
            log.error("WhatsApp payload deserialization failed: outboxSeq={}, error={}",
                    row.getOutboxSeq(), e.getMessage());
            return SendResult.permanentFailure("PAYLOAD_DESERIALIZE", e.getMessage());
        }

        // 3) 템플릿 조회
        Optional<NotificationTemplate> templateOpt = templateRegistry
                .findActive(row.getTemplateCode(), NotificationChannel.WHATSAPP, row.getLocale());
        if (templateOpt.isEmpty()) {
            log.warn("WhatsApp template not found: code={}, locale={}, outboxSeq={}",
                    row.getTemplateCode(), row.getLocale(), row.getOutboxSeq());
            return SendResult.permanentFailure("TEMPLATE_NOT_FOUND",
                    row.getTemplateCode() + " / " + row.getLocale());
        }
        NotificationTemplate template = templateOpt.get();

        // 4) provider_template_name 검증
        String providerTemplateName = template.getProviderTemplateName();
        if (providerTemplateName == null || providerTemplateName.isBlank()) {
            log.error("WhatsApp template missing provider_template_name: code={}, outboxSeq={}",
                    row.getTemplateCode(), row.getOutboxSeq());
            return SendResult.permanentFailure("NO_PROVIDER_TEMPLATE",
                    row.getTemplateCode() + " has no provider_template_name");
        }

        // 5) variables_json → 위치 변수 추출
        List<String> variables;
        try {
            variables = extractVariables(template.getVariablesJson(), payload);
        } catch (IOException e) {
            log.error("WhatsApp variables_json malformed: code={}, outboxSeq={}, error={}",
                    row.getTemplateCode(), row.getOutboxSeq(), e.getMessage());
            return SendResult.permanentFailure("VARIABLES_MALFORMED", e.getMessage());
        }

        // 6) provider 호출
        SendTemplateRequest req = new SendTemplateRequest(
                recipient.getPhoneE164(),
                providerTemplateName,
                row.getLocale(),
                variables,
                row.getIdempotencyKey());

        WhatsappClient.SendResult clientResult;
        try {
            clientResult = whatsappClient.sendTemplate(req);
        } catch (RuntimeException ex) {
            log.error("WhatsApp client unexpected exception (treated as retryable): outboxSeq={}",
                    row.getOutboxSeq(), ex);
            clientResult = WhatsappClient.SendResult.error("UNEXPECTED_EXCEPTION", ex.getMessage());
        }

        // 7) WhatsappMessageLog 저장
        WhatsappMessageLog logRow = WhatsappMessageLog.builder()
                .outboxSeq(row.getOutboxSeq())
                .userSeq(recipient.getUserSeq())
                .phoneE164(recipient.getPhoneE164())
                .templateCode(row.getTemplateCode())
                .templateLocale(row.getLocale())
                .payloadJson(row.getPayloadJson() == null ? "{}" : row.getPayloadJson())
                .provider(whatsappClient.provider())
                .build();
        if (clientResult.isSuccess()) {
            logRow.markQueued(clientResult.providerMessageId());
        } else {
            logRow.markFailed(clientResult.errorCode(), clientResult.errorMessage());
        }
        messageLogRepository.save(logRow);

        // 8) NotificationChannelAdapter.SendResult 매핑
        if (clientResult.isSuccess()) {
            return SendResult.success(clientResult.providerMessageId());
        }
        if (clientResult.isRetryable()) {
            return SendResult.retryableFailure("PROVIDER_ERROR",
                    clientResult.errorCode() + ": " + clientResult.errorMessage());
        }
        return SendResult.permanentFailure("PROVIDER_REJECTED",
                clientResult.errorCode() + ": " + clientResult.errorMessage());
    }

    /**
     * variables_json (예: ["applicantName","applicationSeq","amount"]) 을 파싱해
     * payload Map 에서 같은 키의 값을 순서대로 꺼내 위치 인자 List 로 반환.
     *
     * <p>variables_json 이 null/blank 이면 빈 List 반환 (변수 없는 템플릿).</p>
     */
    private List<String> extractVariables(String variablesJson, Map<String, String> payload) throws IOException {
        if (variablesJson == null || variablesJson.isBlank()) {
            return List.of();
        }
        List<String> keys = objectMapper.readValue(variablesJson, VAR_KEYS_TYPE);
        List<String> out = new ArrayList<>(keys.size());
        for (String key : keys) {
            out.add(payload.getOrDefault(key, ""));
        }
        return out;
    }
}

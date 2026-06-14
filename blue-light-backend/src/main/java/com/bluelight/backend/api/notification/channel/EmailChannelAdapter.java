package com.bluelight.backend.api.notification.channel;

import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.api.email.MailSubjectCode;
import com.bluelight.backend.api.notification.template.NotificationTemplateRegistry;
import com.bluelight.backend.api.notification.template.NotificationTemplateRegistry.TemplateNotFoundException;
import com.bluelight.backend.api.notification.template.RenderedMessage;
import com.bluelight.backend.domain.notification.NotificationChannel;
import com.bluelight.backend.domain.notification.NotificationOutbox;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * 이메일 채널 어댑터 (PR-0C).
 *
 * <p>{@link NotificationOutboxDispatcher} 가 호출. {@link EmailService#sendGenericEmail} 에 위임하며,
 * 활성 구현체는 Spring 의 {@code @Primary} 빈 선택({@code mail.smtp.enabled=true} 면
 * SmtpEmailService, 아니면 LogOnlyEmailService) 으로 결정된다.</p>
 *
 * <h2>발송 가드</h2>
 * <ul>
 *   <li>수신자 user_seq 가 DB 에 없으면 → 영구 실패 (USER_NOT_FOUND).</li>
 *   <li>이메일이 비어 있으면 → 영구 실패 (NO_EMAIL).</li>
 *   <li>사용자가 {@code DELETED} 또는 anonymize 된 경우 → 영구 실패 (USER_INACTIVE).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmailChannelAdapter implements NotificationChannelAdapter {

    private static final TypeReference<Map<String, String>> PAYLOAD_TYPE = new TypeReference<>() {};

    private final EmailService emailService;
    private final NotificationTemplateRegistry templateRegistry;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    /**
     * 활성 Spring 프로필. 운영(prod)이 아닌 환경(개발서버 등)에서는 메일 제목 앞에
     * 템플릿 코드(예: {@code [A-17]})를 붙여 어떤 알림인지 식별하기 쉽게 한다.
     * 운영 서버만 {@code SPRING_PROFILES_ACTIVE=prod} 로 뜨므로, prod 가 아니면 코드를 prefix 한다.
     */
    @Value("${spring.profiles.active:default}")
    private String activeProfiles;

    /**
     * 프론트엔드 베이스 URL — 이메일 CTA의 상대경로(예: {@code /applications/123})를 절대 URL로
     * 만들 때 prepend. 이메일 클라이언트는 상대경로 링크를 못 여므로 필수.
     * password-reset 과 동일 프론트 루트 재사용(환경별 PASSWORD_RESET_BASE_URL 로 주입).
     */
    @Value("${password-reset.base-url:http://localhost:5174}")
    private String frontendBaseUrl;

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public SendResult send(NotificationOutbox row) {
        // 1) 수신자 조회 + 가드
        Optional<User> recipientOpt = userRepository.findById(row.getUserSeq());
        if (recipientOpt.isEmpty()) {
            return SendResult.permanentFailure("USER_NOT_FOUND", "recipient userSeq=" + row.getUserSeq());
        }
        User recipient = recipientOpt.get();
        if (recipient.getStatus() == UserStatus.DELETED) {
            return SendResult.permanentFailure("USER_INACTIVE", "user is DELETED");
        }
        String to = recipient.getEmail();
        if (to == null || to.isBlank() || to.startsWith("deleted-")) {
            return SendResult.permanentFailure("NO_EMAIL", "recipient email is blank or anonymized");
        }

        // 2) payload 역직렬화
        Map<String, String> payload;
        try {
            payload = row.getPayloadJson() == null || row.getPayloadJson().isBlank()
                    ? Map.of()
                    : objectMapper.readValue(row.getPayloadJson(), PAYLOAD_TYPE);
        } catch (IOException e) {
            log.error("Email payload deserialization failed: outboxSeq={}, error={}",
                    row.getOutboxSeq(), e.getMessage());
            return SendResult.permanentFailure("PAYLOAD_DESERIALIZE", e.getMessage());
        }

        // 2b) 이메일 CTA 절대화 — 상대경로(/...) 링크 변수를 프론트 베이스URL 기준 절대 URL로.
        //     이메일 클라이언트는 상대경로를 못 열기 때문. (인앱 채널은 상대경로 그대로 사용)
        payload = absolutizeLinks(payload);

        // 3) 템플릿 렌더
        RenderedMessage rendered;
        try {
            rendered = templateRegistry.render(row.getTemplateCode(), NotificationChannel.EMAIL, row.getLocale(), payload);
        } catch (TemplateNotFoundException e) {
            log.warn("Email template not found: {}, outboxSeq={}", e.getMessage(), row.getOutboxSeq());
            return SendResult.permanentFailure("TEMPLATE_NOT_FOUND", e.getMessage());
        }

        // 4) 발송 위임
        //    운영(prod) 외 환경에서는 제목 앞에 메일 코드(템플릿 코드)를 붙인다. (예: "[A-17] Payment Requested")
        String subject = MailSubjectCode.prefix(activeProfiles, row.getTemplateCode())
                + (rendered.subject() == null ? "" : rendered.subject());
        try {
            emailService.sendGenericEmail(to, subject, rendered.body());
            // SMTP message-id 는 EmailService 가 반환하지 않으므로 outboxSeq 를 추적 식별자로 사용.
            return SendResult.success("outbox-" + row.getOutboxSeq());
        } catch (RuntimeException e) {
            log.warn("Email send failed (treated as retryable): outboxSeq={}, error={}",
                    row.getOutboxSeq(), e.getMessage());
            return SendResult.retryableFailure("SMTP_FAILED", e.getMessage());
        }
    }

    /**
     * payload 의 링크형 값(상대경로 '/...')을 프론트 베이스URL 기준 절대 URL로 변환한다.
     * 이메일에선 상대경로 href 가 동작하지 않으므로 CTA 버튼이 우리 서비스로 연결되도록 보정.
     * 이미 절대 URL(http...)이거나 '/'로 시작하지 않으면 그대로 둔다.
     */
    private Map<String, String> absolutizeLinks(Map<String, String> payload) {
        if (payload == null || payload.isEmpty()) {
            return payload;
        }
        String base = frontendBaseUrl == null ? "" : frontendBaseUrl.replaceAll("/+$", "");
        Map<String, String> out = new java.util.LinkedHashMap<>(payload);
        for (Map.Entry<String, String> e : out.entrySet()) {
            String v = e.getValue();
            if (v != null && v.startsWith("/") && !v.startsWith("//")) {
                e.setValue(base + v);
            }
        }
        return out;
    }
}

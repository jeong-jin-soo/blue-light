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
}

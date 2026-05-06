package com.bluelight.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

/**
 * 이메일 설정
 * - mail.smtp.enabled=true 일 때만 JavaMailSender 빈 생성
 * - 미설정 시 LogOnlyEmailService가 폴백으로 활성화
 *
 * <h3>포트별 TLS 모드</h3>
 * <ul>
 *   <li><b>465 (SMTPS, implicit SSL)</b>: 연결 즉시 SSL 핸드셰이크 — Resend, Gmail, AWS SES 모두 지원</li>
 *   <li><b>587 (Submission, STARTTLS)</b>: 평문 연결 후 STARTTLS 명령으로 업그레이드</li>
 *   <li><b>25 (legacy)</b>: 평문 — 운영 사용 비권장</li>
 * </ul>
 * 두 모드를 한 번에 켜면 RFC 위반으로 서버가 즉시 끊어버려 발송 실패가 silent로 사라짐.
 */
@Slf4j
@Configuration
public class MailConfig {

    @Bean
    @ConditionalOnProperty(name = "mail.smtp.enabled", havingValue = "true")
    public JavaMailSender javaMailSender(
            @Value("${spring.mail.host}") String host,
            @Value("${spring.mail.port:587}") int port,
            @Value("${spring.mail.username:}") String username,
            @Value("${spring.mail.password:}") String password
    ) {
        boolean implicitSsl = (port == 465);
        log.info("SMTP enabled — host={}, port={}, mode={}", host, port,
                implicitSsl ? "SMTPS(implicit-SSL)" : "STARTTLS");
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(host);
        mailSender.setPort(port);
        mailSender.setUsername(username);
        mailSender.setPassword(password);

        Properties props = mailSender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");

        if (implicitSsl) {
            // 465 = SMTPS — 연결 즉시 SSL 핸드셰이크 (Resend, Gmail SSL, SES SMTPS)
            props.put("mail.smtp.ssl.enable", "true");
            // STARTTLS는 비활성화 (켜두면 SSL 위에 STARTTLS 시도하다 실패)
            props.put("mail.smtp.starttls.enable", "false");
        } else {
            // 587 = STARTTLS (AWS SES SMTP 기본, 표준 메일 제출 포트)
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
        }

        return mailSender;
    }
}

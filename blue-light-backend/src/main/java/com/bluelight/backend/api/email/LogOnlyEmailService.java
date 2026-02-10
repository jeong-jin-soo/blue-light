package com.bluelight.backend.api.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

/**
 * 개발용 이메일 서비스 (로그 출력만)
 * - SMTP 설정이 없을 때 자동 활성화
 * - 콘솔에 비밀번호 재설정 링크를 출력
 */
@Slf4j
@Service
@ConditionalOnMissingBean(SmtpEmailService.class)
public class LogOnlyEmailService implements EmailService {

    @Override
    public void sendPasswordResetEmail(String to, String userName, String resetLink) {
        log.info("==================================================");
        log.info("[DEV] Password Reset Email (not actually sent)");
        log.info("  To: {}", to);
        log.info("  Name: {}", userName);
        log.info("  Reset Link: {}", resetLink);
        log.info("==================================================");
    }
}

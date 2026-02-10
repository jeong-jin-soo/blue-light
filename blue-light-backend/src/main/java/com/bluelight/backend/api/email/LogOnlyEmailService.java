package com.bluelight.backend.api.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 개발용 이메일 서비스 (로그 출력만)
 * - 항상 등록, SMTP 설정 시 SmtpEmailService가 @Primary로 우선
 * - 콘솔에 비밀번호 재설정 링크를 출력
 */
@Slf4j
@Service
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

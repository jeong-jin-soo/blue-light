package com.bluelight.backend.api.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 개발용 이메일 서비스 (로그 출력만)
 * - 항상 등록, SMTP 설정 시 SmtpEmailService가 @Primary로 우선
 * - 콘솔에 이메일 내용을 출력
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

    @Override
    public void sendLicenseExpiryWarningEmail(String to, String userName,
                                               String licenseNumber, String address,
                                               LocalDate expiryDate, int daysRemaining) {
        log.info("==================================================");
        log.info("[DEV] License Expiry Warning Email (not actually sent)");
        log.info("  To: {}", to);
        log.info("  Name: {}", userName);
        log.info("  License Number: {}", licenseNumber);
        log.info("  Address: {}", address);
        log.info("  Expiry Date: {}", expiryDate);
        log.info("  Days Remaining: {}", daysRemaining);
        log.info("==================================================");
    }
}

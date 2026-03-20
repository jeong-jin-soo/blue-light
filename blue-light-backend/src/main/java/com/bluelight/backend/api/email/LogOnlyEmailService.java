package com.bluelight.backend.api.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
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
    public void sendEmailVerificationEmail(String to, String userName, String verificationLink) {
        log.info("==================================================");
        log.info("[DEV] Email Verification Email (not actually sent)");
        log.info("  To: {}", to);
        log.info("  Name: {}", userName);
        log.info("  Verification Link: {}", verificationLink);
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

    @Override
    public void sendRevisionRequestEmail(String to, String userName, Long appSeq, String address, String comment) {
        log.info("==================================================");
        log.info("[DEV] Revision Request Email (not actually sent)");
        log.info("  To: {}", to);
        log.info("  Name: {}", userName);
        log.info("  Application: #{}", appSeq);
        log.info("  Address: {}", address);
        log.info("  Comment: {}", comment);
        log.info("==================================================");
    }

    @Override
    public void sendPaymentRequestEmail(String to, String userName, Long appSeq, String address, BigDecimal amount) {
        log.info("==================================================");
        log.info("[DEV] Payment Request Email (not actually sent)");
        log.info("  To: {}", to);
        log.info("  Name: {}", userName);
        log.info("  Application: #{}", appSeq);
        log.info("  Address: {}", address);
        log.info("  Amount: ${}", amount);
        log.info("==================================================");
    }

    @Override
    public void sendPaymentConfirmEmail(String to, String userName, Long appSeq, String address, BigDecimal amount) {
        log.info("==================================================");
        log.info("[DEV] Payment Confirm Email (not actually sent)");
        log.info("  To: {}", to);
        log.info("  Name: {}", userName);
        log.info("  Application: #{}", appSeq);
        log.info("  Address: {}", address);
        log.info("  Amount: ${}", amount);
        log.info("==================================================");
    }

    @Override
    public void sendLicenseIssuedEmail(String to, String userName, Long appSeq,
                                        String address, String licenseNo, LocalDate expiryDate) {
        log.info("==================================================");
        log.info("[DEV] License Issued Email (not actually sent)");
        log.info("  To: {}", to);
        log.info("  Name: {}", userName);
        log.info("  Application: #{}", appSeq);
        log.info("  Address: {}", address);
        log.info("  License No: {}", licenseNo);
        log.info("  Expiry Date: {}", expiryDate);
        log.info("==================================================");
    }

    @Override
    public void sendLewAssignedEmail(String to, String lewName, Long appSeq, String address, String applicantName) {
        log.info("==================================================");
        log.info("[DEV] LEW Assigned Email (not actually sent)");
        log.info("  To: {}", to);
        log.info("  LEW Name: {}", lewName);
        log.info("  Application: #{}", appSeq);
        log.info("  Address: {}", address);
        log.info("  Applicant: {}", applicantName);
        log.info("==================================================");
    }

    @Override
    public void sendPaymentConfirmedToLewEmail(String to, String lewName, Long appSeq, String address, BigDecimal amount) {
        log.info("==================================================");
        log.info("[DEV] Payment Confirmed to LEW Email (not actually sent)");
        log.info("  To: {}", to);
        log.info("  LEW Name: {}", lewName);
        log.info("  Application: #{}", appSeq);
        log.info("  Address: {}", address);
        log.info("  Amount: ${}", amount);
        log.info("==================================================");
    }
}

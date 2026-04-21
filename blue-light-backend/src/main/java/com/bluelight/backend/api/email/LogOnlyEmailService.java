package com.bluelight.backend.api.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

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

    // ── Phase 3 PR#4 · Document Request Workflow ──

    @Override
    public void sendDocumentRequestCreatedEmail(String to, String userName, Long appSeq,
                                                 int requestedCount, List<String> documentLabels) {
        log.info("==================================================");
        log.info("[DEV] Document Request Created Email (not actually sent)");
        log.info("  To: {}", to);
        log.info("  Name: {}", userName);
        log.info("  Application: #{}", appSeq);
        log.info("  Requested Count: {}", requestedCount);
        log.info("  Labels: {}", documentLabels);
        log.info("==================================================");
    }

    @Override
    public void sendDocumentRequestFulfilledEmail(String to, String lewName, Long appSeq, String documentLabel) {
        log.info("==================================================");
        log.info("[DEV] Document Request Fulfilled Email (not actually sent)");
        log.info("  To: {}", to);
        log.info("  LEW Name: {}", lewName);
        log.info("  Application: #{}", appSeq);
        log.info("  Document: {}", documentLabel);
        log.info("==================================================");
    }

    @Override
    public void sendDocumentRequestApprovedEmail(String to, String userName, Long appSeq, String documentLabel) {
        log.info("==================================================");
        log.info("[DEV] Document Request Approved Email (not actually sent)");
        log.info("  To: {}", to);
        log.info("  Name: {}", userName);
        log.info("  Application: #{}", appSeq);
        log.info("  Document: {}", documentLabel);
        log.info("==================================================");
    }

    @Override
    public void sendDocumentRequestRejectedEmail(String to, String userName, Long appSeq,
                                                  String documentLabel, String rejectionReason) {
        log.info("==================================================");
        log.info("[DEV] Document Request Rejected Email (not actually sent)");
        log.info("  To: {}", to);
        log.info("  Name: {}", userName);
        log.info("  Application: #{}", appSeq);
        log.info("  Document: {}", documentLabel);
        log.info("  Reason: {}", rejectionReason);
        log.info("==================================================");
    }

    // ── Kaki Concierge Phase 1 PR#2 ──────────────────────

    @Override
    public void sendAccountSetupLinkEmail(String to, String fullName, String setupUrl, String expiresAtDisplay) {
        log.info("==================================================");
        log.info("[DEV] Account Setup Link Email (not actually sent)");
        log.info("  To: {}", to);
        log.info("  Name: {}", fullName);
        log.info("  Setup URL: {}", setupUrl);
        log.info("  Expires At: {}", expiresAtDisplay);
        log.info("==================================================");
    }

    @Override
    public void sendConciergeRequestReceivedEmail(String to, String fullName, String setupUrl, String expiresAtDisplay) {
        log.info("==================================================");
        log.info("[DEV] Concierge Request Received (N1) Email (not actually sent)");
        log.info("  To: {}", to);
        log.info("  Name: {}", fullName);
        log.info("  Setup URL: {}", setupUrl);
        log.info("  Expires At: {}", expiresAtDisplay);
        log.info("==================================================");
    }

    @Override
    public void sendConciergeRequestReceivedExistingUserEmail(String to, String fullName) {
        log.info("==================================================");
        log.info("[DEV] Concierge Request Received - Existing User (N1-Alt) (not actually sent)");
        log.info("  To: {}", to);
        log.info("  Name: {}", fullName);
        log.info("==================================================");
    }

    @Override
    public void sendConciergeStaffNewRequestEmail(String to, String staffName, String publicCode,
                                                   String applicantName, String applicantEmail) {
        log.info("==================================================");
        log.info("[DEV] Concierge Staff New Request (N2) Email (not actually sent)");
        log.info("  To: {}", to);
        log.info("  Staff: {}", staffName);
        log.info("  Public Code: {}", publicCode);
        log.info("  Applicant: {} <{}>", applicantName, applicantEmail);
        log.info("==================================================");
    }

    @Override
    public void sendConciergeLoaUploadConfirmEmail(String to, String applicantName,
                                                    String managerName, Long applicationSeq,
                                                    String memo) {
        log.info("==================================================");
        log.info("[DEV] Concierge LOA Upload Confirm (N5-UploadConfirm) Email (not actually sent)");
        log.info("  To: {}", to);
        log.info("  Applicant: {}", applicantName);
        log.info("  Manager: {}", managerName);
        log.info("  Application: #{}", applicationSeq);
        log.info("  Memo: {}", memo == null ? "(none)" : memo);
        log.info("==================================================");
    }

    @Override
    public String sendConciergeQuoteEmail(String to, String applicantName, String publicCode,
                                           java.math.BigDecimal quotedAmount,
                                           java.time.LocalDateTime callScheduledAt,
                                           String managerNote, String verificationPhrase,
                                           String paynowUen, String paynowAccountName) {
        log.info("==================================================");
        log.info("[DEV] Concierge Quote Email (Phase 1.5) (not actually sent)");
        log.info("  To: {}", to);
        log.info("  Applicant: {}", applicantName);
        log.info("  PublicCode: {}", publicCode);
        log.info("  QuotedAmount: SGD {}", quotedAmount);
        log.info("  CallScheduledAt: {}", callScheduledAt);
        log.info("  Note: {}", managerNote == null ? "(none)" : managerNote);
        log.info("  VerificationPhrase: {}", verificationPhrase);
        log.info("  PayNow UEN: {} / Name: {}", paynowUen, paynowAccountName);
        log.info("==================================================");
        return "dev-msg-" + publicCode;
    }
}

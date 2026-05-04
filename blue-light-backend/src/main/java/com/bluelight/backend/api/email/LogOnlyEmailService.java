package com.bluelight.backend.api.email;

import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class LogOnlyEmailService implements EmailService {

    /** PR-3: 미리보기 HTML 빌더 — SMTP 발송 본문과 동일한 결과를 위해 단일 컴포넌트 사용. */
    private final ManualEmailHtmlRenderer manualEmailHtmlRenderer;

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

    @Override
    public void sendKvaAdjustedToLewEmail(String to, String lewName, Long appSeq,
                                          Integer previousKva, Integer newKva,
                                          BigDecimal previousQuoteAmount, BigDecimal newQuoteAmount,
                                          BigDecimal amountDifference,
                                          boolean cofReissueTriggered, String reason) {
        log.info("==================================================");
        log.info("[DEV] kVA Adjusted by Admin to LEW Email (not actually sent)");
        log.info("  To: {}", to);
        log.info("  LEW Name: {}", lewName);
        log.info("  Application: #{}", appSeq);
        log.info("  Previous kVA: {}", previousKva);
        log.info("  New kVA: {}", newKva);
        log.info("  Previous Quote: ${}", previousQuoteAmount);
        log.info("  New Quote: ${}", newQuoteAmount);
        log.info("  Difference: ${}", amountDifference);
        log.info("  CoF Reissue Triggered: {}", cofReissueTriggered);
        log.info("  Reason: {}", reason);
        log.info("==================================================");
    }

    @Override
    public void sendKvaSettlementMarkedToLewEmail(String to, String lewName, Long appSeq,
                                                   String paymentAdjustment,
                                                   BigDecimal settledAmount,
                                                   String receiptReferenceNumber) {
        log.info("==================================================");
        log.info("[DEV] kVA Settlement Marked to LEW Email (not actually sent)");
        log.info("  To: {}", to);
        log.info("  LEW Name: {}", lewName);
        log.info("  Application: #{}", appSeq);
        log.info("  Payment Adjustment: {}", paymentAdjustment);
        log.info("  Settled Amount: {}", settledAmount == null ? "(none)" : "$" + settledAmount);
        log.info("  Receipt Ref: {}", receiptReferenceNumber == null ? "(none)" : receiptReferenceNumber);
        log.info("==================================================");
    }

    @Override
    public void sendKvaAdjustmentRequestedToAdminEmail(String to, String adminName, String lewName, Long appSeq,
                                                        Integer proposedKva, Integer currentKva, String reason) {
        log.info("==================================================");
        log.info("[DEV] kVA Adjustment Requested by LEW (to ADMIN) Email (not actually sent)");
        log.info("  To: {}", to);
        log.info("  Admin Name: {}", adminName);
        log.info("  LEW Name: {}", lewName);
        log.info("  Application: #{}", appSeq);
        log.info("  Current kVA: {}", currentKva);
        log.info("  Proposed kVA: {}", proposedKva);
        log.info("  Reason: {}", reason);
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
    public void sendManualPlainTextEmail(String to, String subject, String bodyText, String adminEmailForFooter) {
        // 개발 환경 — 실제 발송 없이 콘솔에만 표시. 본문은 100자 이상이면 truncate.
        // SmtpEmailService 와 달리 RuntimeException 을 던지지 않으며, 항상 성공으로 간주된다.
        String preview = bodyText == null ? "" : bodyText;
        if (preview.length() > 200) {
            preview = preview.substring(0, 200) + "…(truncated)";
        }
        log.info("==================================================");
        log.info("[DEV] Manual Email (admin → recipient) (not actually sent)");
        log.info("  To: {}", to);
        log.info("  From admin: {}", adminEmailForFooter);
        log.info("  Subject: {}", subject);
        log.info("  Body preview: {}", preview);
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

    @Override
    public void sendInvoiceIssuedEmail(String to, String recipientName, String invoiceNumber,
                                        BigDecimal amount, String currency,
                                        byte[] attachmentBytes, String attachmentFilename) {
        log.info("==================================================");
        log.info("[DEV] Invoice Issued Email (not actually sent)");
        log.info("  To: {}", to);
        log.info("  Recipient: {}", recipientName);
        log.info("  Invoice Number: {}", invoiceNumber);
        log.info("  Amount: {} {}", currency, amount);
        log.info("  Attachment: {} ({} bytes)",
                attachmentFilename,
                attachmentBytes != null ? attachmentBytes.length : 0);
        log.info("==================================================");
    }

    @Override
    public String renderManualPlainTextHtml(String subject, String bodyText, String adminEmailForFooter) {
        // 개발 환경 — SMTP 와 동일한 HTML 을 반환해야 미리보기가 환경 따라 달라지지 않는다.
        // 별도 로그는 남기지 않는다 (preview 호출은 빈번할 수 있어 노이즈 방지).
        return manualEmailHtmlRenderer.render(bodyText, adminEmailForFooter);
    }

    @Override
    public void sendConciergeLewAssignedEmail(String to, String lewName, String publicCode,
                                                String applicantName, String applicantEmail,
                                                String applicantPhone, String memo,
                                                boolean reassigned) {
        log.info("==================================================");
        log.info("[DEV] Concierge LEW Assigned Email (PR-3) (not actually sent)");
        log.info("  To: {}", to);
        log.info("  LEW Name: {}", lewName);
        log.info("  PublicCode: {}", publicCode);
        log.info("  Applicant: {} <{}> {}", applicantName, applicantEmail, applicantPhone);
        log.info("  Memo: {}", memo == null || memo.isBlank() ? "(none)" : memo);
        log.info("  Reassigned: {}", reassigned);
        log.info("==================================================");
    }

    @Override
    public void sendConciergeLewUnassignedEmail(String to, String lewName, String publicCode) {
        log.info("==================================================");
        log.info("[DEV] Concierge LEW Unassigned Email (PR-3) (not actually sent)");
        log.info("  To: {}", to);
        log.info("  LEW Name: {}", lewName);
        log.info("  PublicCode: {}", publicCode);
        log.info("==================================================");
    }
}

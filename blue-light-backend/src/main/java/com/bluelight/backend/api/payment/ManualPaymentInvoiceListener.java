package com.bluelight.backend.api.payment;

import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.api.file.FileStorageService;
import com.bluelight.backend.api.invoice.InvoiceGenerationService;
import com.bluelight.backend.api.notification.NotificationService;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.file.FileEntity;
import com.bluelight.backend.domain.file.FileRepository;
import com.bluelight.backend.domain.invoice.Invoice;
import com.bluelight.backend.domain.notification.NotificationType;
import com.bluelight.backend.domain.payment.Payment;
import com.bluelight.backend.domain.payment.PaymentReferenceType;
import com.bluelight.backend.domain.payment.PaymentRepository;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * ★ Concierge 강화 + 별도 수금 + 영수증 자동 발행 PR-2 — 별도 수금 기록 직후 후속 작업 처리.
 *
 * <p>스펙: {@code doc/Project Analysis/concierge-flow-and-offline-payment-spec.md} §8, AC-R1~R3.</p>
 *
 * <h3>왜 AFTER_COMMIT 인가</h3>
 * 별도 수금 트랜잭션의 본질은 Payment row + Application/Concierge 상태 전이이며, Invoice 발행 + PDF 렌더 +
 * SMTP 발송은 부수 효과다. 외부 의존(파일스토리지, SMTP)의 일시 오류가 결제 트랜잭션을 롤백시키면 안 된다
 * (스펙 D5=B). 따라서 본 listener 는 {@link TransactionPhase#AFTER_COMMIT} 으로 분리한다.
 *
 * <h3>책임 (순서)</h3>
 * <ol>
 *   <li>{@code receiptIssue=false} (AC-A5) → invoice/email/notification 모두 스킵.</li>
 *   <li>Invoice 자동 발행 ({@link InvoiceGenerationService#generateFromPayment(Payment)} dispatcher).</li>
 *   <li>영수증 PDF 첨부 이메일 발송 (실패 시 audit FAILED 마킹).</li>
 *   <li>인앱 알림 2종 ({@link NotificationType#MANUAL_PAYMENT_CONFIRMED_APPLICANT},
 *       {@link NotificationType#INVOICE_ISSUED_APPLICANT}) — 영수증 미발행 시에는 결제 확인 알림만.</li>
 * </ol>
 *
 * <h3>실패 격리</h3>
 * AFTER_COMMIT 단계의 어떤 예외도 결제 트랜잭션을 롤백시킬 수 없으나, 호출자(이벤트 디스패처) 로그
 * 노이즈와 후속 listener 영향 방지를 위해 모든 단계에서 try/catch.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ManualPaymentInvoiceListener {

    /** 인앱 알림 referenceType (NotificationsPage 라우팅 키) */
    static final String REFERENCE_TYPE_APPLICATION = "APPLICATION";
    static final String REFERENCE_TYPE_CONCIERGE = "CONCIERGE_REQUEST";

    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final FileRepository fileRepository;
    private final FileStorageService fileStorageService;
    private final InvoiceGenerationService invoiceGenerationService;
    private final EmailService emailService;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;

    /**
     * AFTER_COMMIT 단계에서 호출되므로 호출 시점엔 트랜잭션이 종료된 상태다. 그러나 본 listener 가
     * Payment/User/FileEntity 등 Lazy 로딩과 영속성 컨텍스트 의존 조회를 수행해야 하므로
     * {@code REQUIRES_NEW} 로 신규 트랜잭션을 시작한다 — 결제 트랜잭션과는 완전히 독립이라 실패해도
     * 결제 보존은 유지된다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onManualPaymentRecorded(ManualPaymentRecordedEvent event) {
        try {
            // 1) 결제 확인 인앱 알림 (receiptIssue 와 무관하게 항상 발송).
            sendManualPaymentConfirmedNotification(event);

            if (!event.isReceiptIssue()) {
                log.info("Manual payment receipt skipped (receiptIssue=false): paymentSeq={}",
                        event.getPaymentSeq());
                return;
            }

            // 2) Invoice 발행 — 실패 시 결제는 보존.
            Invoice invoice = generateInvoiceSafely(event);
            if (invoice == null) {
                return; // 발행 실패 — 후속 단계 스킵 (audit 는 이미 기록됨).
            }

            // 3) 영수증 이메일 발송 — 실패 시 audit FAILED.
            sendInvoiceEmailSafely(event, invoice);

            // 4) 영수증 발행 인앱 알림.
            sendInvoiceIssuedNotification(event, invoice);

        } catch (RuntimeException ex) {
            log.error("ManualPaymentInvoiceListener failed (swallowed): paymentSeq={}, err={}",
                    event.getPaymentSeq(), ex.getMessage(), ex);
        }
    }

    // ────────────────────────────────────────────────────────────
    // Invoice 발행 (실패 격리)
    // ────────────────────────────────────────────────────────────

    /**
     * Invoice 자동 발행. AFTER_COMMIT 단계이므로 별도 트랜잭션이 필요하다 — InvoiceGenerationService 의
     * 메서드 자체가 {@code @Transactional} 로 별도 트랜잭션을 시작하므로 직접 호출만으로 충분.
     * 실패 시 audit FAILED + null 반환.
     */
    private Invoice generateInvoiceSafely(ManualPaymentRecordedEvent event) {
        try {
            Payment payment = paymentRepository.findById(event.getPaymentSeq()).orElse(null);
            if (payment == null) {
                log.warn("Manual payment invoice skipped — payment not found: paymentSeq={}",
                        event.getPaymentSeq());
                recordInvoiceAuditFailed(event, "PAYMENT_NOT_FOUND");
                return null;
            }
            Invoice invoice = invoiceGenerationService.generateFromPayment(payment);
            recordInvoiceAuditSuccess(event, invoice);
            return invoice;
        } catch (RuntimeException ex) {
            log.warn("Manual payment invoice generation failed: paymentSeq={}, err={}",
                    event.getPaymentSeq(), ex.getMessage());
            recordInvoiceAuditFailed(event, ex.getMessage());
            return null;
        }
    }

    private void recordInvoiceAuditSuccess(ManualPaymentRecordedEvent event, Invoice invoice) {
        try {
            String entityId = String.valueOf(invoice.getInvoiceSeq());
            String description = "Invoice auto-generated from manual payment: paymentSeq=" + event.getPaymentSeq()
                    + ", invoiceNumber=" + invoice.getInvoiceNumber()
                    + ", method=" + event.getPaymentMethod();
            auditLogService.log(
                    event.getRecordedByUserSeq(), null, null,
                    AuditAction.INVOICE_AUTO_GENERATED_FROM_MANUAL_PAYMENT,
                    AuditCategory.ADMIN,
                    "Invoice", entityId,
                    description,
                    null, null, null, null, null, null, null);
        } catch (RuntimeException ex) {
            log.warn("Audit (INVOICE_AUTO_GENERATED_FROM_MANUAL_PAYMENT success) failed: paymentSeq={}, err={}",
                    event.getPaymentSeq(), ex.getMessage());
        }
    }

    private void recordInvoiceAuditFailed(ManualPaymentRecordedEvent event, String reason) {
        try {
            auditLogService.log(
                    event.getRecordedByUserSeq(), null, null,
                    AuditAction.INVOICE_AUTO_GENERATED_FROM_MANUAL_PAYMENT,
                    AuditCategory.ADMIN,
                    "Payment", String.valueOf(event.getPaymentSeq()),
                    "FAILED: invoice auto-generation failed: " + reason,
                    null, null, null, null, null, null, 500);
        } catch (RuntimeException ex) {
            log.warn("Audit (INVOICE_AUTO_GENERATED_FROM_MANUAL_PAYMENT failed) failed: paymentSeq={}, err={}",
                    event.getPaymentSeq(), ex.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────
    // 이메일 발송 (실패 격리)
    // ────────────────────────────────────────────────────────────

    private void sendInvoiceEmailSafely(ManualPaymentRecordedEvent event, Invoice invoice) {
        try {
            User recipient = userRepository.findById(event.getApplicantUserSeq()).orElse(null);
            if (recipient == null || recipient.getEmail() == null || recipient.getEmail().isBlank()) {
                log.warn("Manual payment email skipped — recipient or email missing: applicantUserSeq={}",
                        event.getApplicantUserSeq());
                return;
            }

            byte[] pdfBytes = loadInvoicePdfBytes(invoice);
            String filename = "INVOICE_" + invoice.getInvoiceNumber() + ".pdf";

            emailService.sendInvoiceIssuedEmail(
                    recipient.getEmail(),
                    recipient.getFullName(),
                    invoice.getInvoiceNumber(),
                    invoice.getTotalAmount(),
                    invoice.getCurrencySnapshot(),
                    pdfBytes,
                    filename);
        } catch (RuntimeException ex) {
            // 이메일 실패는 D5=B — 결제·invoice 보존, audit FAILED 마킹.
            log.warn("Manual payment invoice email failed: paymentSeq={}, invoiceSeq={}, err={}",
                    event.getPaymentSeq(), invoice.getInvoiceSeq(), ex.getMessage());
            try {
                auditLogService.log(
                        event.getRecordedByUserSeq(), null, null,
                        AuditAction.INVOICE_AUTO_GENERATED_FROM_MANUAL_PAYMENT,
                        AuditCategory.ADMIN,
                        "Invoice", String.valueOf(invoice.getInvoiceSeq()),
                        "FAILED: invoice email delivery failed: " + ex.getMessage(),
                        null, null, null, null, null, null, 500);
            } catch (RuntimeException auditEx) {
                log.warn("Audit (email failed) failed: paymentSeq={}, err={}",
                        event.getPaymentSeq(), auditEx.getMessage());
            }
        }
    }

    /**
     * Invoice.pdfFileSeq → FileEntity → FileStorageService.loadAsResource → bytes.
     * 첨부 실패는 RuntimeException 으로 전파하여 호출자가 audit 마킹.
     */
    private byte[] loadInvoicePdfBytes(Invoice invoice) {
        Long pdfFileSeq = invoice.getPdfFileSeq();
        if (pdfFileSeq == null) {
            // PDF 가 없는 invoice 는 비정상 상태 — 첨부 없이 발송.
            return null;
        }
        FileEntity file = fileRepository.findById(pdfFileSeq).orElse(null);
        if (file == null || file.getFileUrl() == null) {
            return null;
        }
        try {
            Resource resource = fileStorageService.loadAsResource(file.getFileUrl());
            return resource.getInputStream().readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to load invoice PDF (fileSeq=" + pdfFileSeq + "): " + e.getMessage(), e);
        }
    }

    // ────────────────────────────────────────────────────────────
    // 인앱 알림
    // ────────────────────────────────────────────────────────────

    private void sendManualPaymentConfirmedNotification(ManualPaymentRecordedEvent event) {
        try {
            String referenceType = event.getReferenceType() == PaymentReferenceType.APPLICATION
                    ? REFERENCE_TYPE_APPLICATION : REFERENCE_TYPE_CONCIERGE;
            Long referenceId = event.getReferenceType() == PaymentReferenceType.APPLICATION
                    ? event.getApplicationSeq() : event.getConciergeRequestSeq();

            String title = "Payment received";
            String body = referenceType.equals(REFERENCE_TYPE_APPLICATION)
                    ? "Your payment for application #" + event.getApplicationSeq() + " has been recorded."
                    : "Your concierge service payment has been recorded.";

            notificationService.createNotification(
                    event.getApplicantUserSeq(),
                    NotificationType.MANUAL_PAYMENT_CONFIRMED_APPLICANT,
                    title, body, referenceType, referenceId);
        } catch (RuntimeException ex) {
            log.warn("Manual payment confirmed notification failed: applicantUserSeq={}, err={}",
                    event.getApplicantUserSeq(), ex.getMessage());
        }
    }

    private void sendInvoiceIssuedNotification(ManualPaymentRecordedEvent event, Invoice invoice) {
        try {
            String referenceType = event.getReferenceType() == PaymentReferenceType.APPLICATION
                    ? REFERENCE_TYPE_APPLICATION : REFERENCE_TYPE_CONCIERGE;
            Long referenceId = event.getReferenceType() == PaymentReferenceType.APPLICATION
                    ? event.getApplicationSeq() : event.getConciergeRequestSeq();

            String title = "Receipt issued";
            String body = "Receipt #" + invoice.getInvoiceNumber() + " has been emailed to you.";

            notificationService.createNotification(
                    event.getApplicantUserSeq(),
                    NotificationType.INVOICE_ISSUED_APPLICANT,
                    title, body, referenceType, referenceId);
        } catch (RuntimeException ex) {
            log.warn("Invoice issued notification failed: applicantUserSeq={}, err={}",
                    event.getApplicantUserSeq(), ex.getMessage());
        }
    }
}

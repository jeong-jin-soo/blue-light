package com.bluelight.backend.api.payment;

import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.api.file.FileStorageService;
import com.bluelight.backend.api.invoice.InvoiceGenerationService;
import com.bluelight.backend.api.notification.NotificationService;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.file.FileEntity;
import com.bluelight.backend.domain.file.FileRepository;
import com.bluelight.backend.domain.invoice.Invoice;
import com.bluelight.backend.domain.notification.NotificationType;
import com.bluelight.backend.domain.payment.Payment;
import com.bluelight.backend.domain.payment.PaymentMethod;
import com.bluelight.backend.domain.payment.PaymentReferenceType;
import com.bluelight.backend.domain.payment.PaymentRepository;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.math.BigDecimal;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ManualPaymentInvoiceListener — AC-R1 / AC-R2 / AC-R3 + receiptIssue=false 스킵 검증.
 */
@DisplayName("ManualPaymentInvoiceListener — AFTER_COMMIT 영수증 발행/이메일/알림 (AC-R1~R3)")
class ManualPaymentInvoiceListenerTest {

    private PaymentRepository paymentRepository;
    private UserRepository userRepository;
    private FileRepository fileRepository;
    private FileStorageService fileStorageService;
    private InvoiceGenerationService invoiceGenerationService;
    private EmailService emailService;
    private NotificationService notificationService;
    private AuditLogService auditLogService;
    private ManualPaymentInvoiceListener listener;

    private static final Long PAYMENT_SEQ = 555L;
    private static final Long APP_SEQ = 200L;
    private static final Long APPLICANT_SEQ = 77L;
    private static final Long ADMIN_SEQ = 99L;
    private static final Long INVOICE_SEQ = 999L;
    private static final Long PDF_FILE_SEQ = 1234L;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        userRepository = mock(UserRepository.class);
        fileRepository = mock(FileRepository.class);
        fileStorageService = mock(FileStorageService.class);
        invoiceGenerationService = mock(InvoiceGenerationService.class);
        emailService = mock(EmailService.class);
        notificationService = mock(NotificationService.class);
        auditLogService = mock(AuditLogService.class);

        listener = new ManualPaymentInvoiceListener(
                paymentRepository, userRepository, fileRepository, fileStorageService,
                invoiceGenerationService, emailService, notificationService, auditLogService);
    }

    private ManualPaymentRecordedEvent applicationEvent(boolean receiptIssue) {
        return new ManualPaymentRecordedEvent(
                PAYMENT_SEQ, APPLICANT_SEQ,
                PaymentReferenceType.APPLICATION,
                APP_SEQ, null,
                new BigDecimal("350.00"), PaymentMethod.BANK_TRANSFER,
                receiptIssue, ADMIN_SEQ);
    }

    private void stubBaseDependencies() {
        Payment payment = mock(Payment.class);
        when(paymentRepository.findById(PAYMENT_SEQ)).thenReturn(Optional.of(payment));

        User recipient = mock(User.class);
        when(recipient.getEmail()).thenReturn("applicant@test.sg");
        when(recipient.getFullName()).thenReturn("Test User");
        when(userRepository.findById(APPLICANT_SEQ)).thenReturn(Optional.of(recipient));

        Invoice invoice = mock(Invoice.class);
        when(invoice.getInvoiceSeq()).thenReturn(INVOICE_SEQ);
        when(invoice.getInvoiceNumber()).thenReturn("LK-RCP-20260501-0001");
        when(invoice.getTotalAmount()).thenReturn(new BigDecimal("350.00"));
        when(invoice.getCurrencySnapshot()).thenReturn("SGD");
        when(invoice.getPdfFileSeq()).thenReturn(PDF_FILE_SEQ);
        when(invoiceGenerationService.generateFromPayment(payment)).thenReturn(invoice);

        FileEntity file = mock(FileEntity.class);
        when(file.getFileUrl()).thenReturn("invoices/200/INVOICE_x.pdf");
        when(fileRepository.findById(PDF_FILE_SEQ)).thenReturn(Optional.of(file));

        Resource resource = new ByteArrayResource(new byte[]{1, 2, 3, 4, 5});
        when(fileStorageService.loadAsResource(anyString())).thenReturn(resource);
    }

    // ── AC-R1: 정상 경로 — invoice 발행 + PDF 첨부 이메일 + 인앱 알림 2종 ──
    @Test
    @DisplayName("AC-R1: receiptIssue=true → Invoice 발행 + 첨부 이메일 + 알림 2종")
    void shouldGenerateInvoiceAndSendEmailAndCreateNotifications() {
        stubBaseDependencies();

        listener.onManualPaymentRecorded(applicationEvent(true));

        verify(invoiceGenerationService).generateFromPayment(any(Payment.class));
        verify(emailService).sendInvoiceIssuedEmail(
                eq("applicant@test.sg"), eq("Test User"),
                eq("LK-RCP-20260501-0001"),
                eq(new BigDecimal("350.00")),
                eq("SGD"),
                any(byte[].class), anyString());
        // 인앱 알림 2종: MANUAL_PAYMENT_CONFIRMED_APPLICANT + INVOICE_ISSUED_APPLICANT.
        verify(notificationService).createNotification(
                eq(APPLICANT_SEQ), eq(NotificationType.MANUAL_PAYMENT_CONFIRMED_APPLICANT),
                anyString(), anyString(), eq("APPLICATION"), eq(APP_SEQ));
        verify(notificationService).createNotification(
                eq(APPLICANT_SEQ), eq(NotificationType.INVOICE_ISSUED_APPLICANT),
                anyString(), anyString(), eq("APPLICATION"), eq(APP_SEQ));
        // Invoice 자동 발행 audit (성공)
        verify(auditLogService).log(any(), any(), any(),
                eq(AuditAction.INVOICE_AUTO_GENERATED_FROM_MANUAL_PAYMENT),
                any(), any(), any(),
                org.mockito.ArgumentMatchers.contains("Invoice auto-generated from manual payment"),
                any(), any(), any(), any(), any(), any(), any());
    }

    // ── receiptIssue=false → invoice/email 스킵, 결제 확인 알림만 ──
    @Test
    @DisplayName("receiptIssue=false → invoice/email 스킵, 결제 확인 알림만 발송")
    void shouldSkipReceiptWhenReceiptIssueFalse() {
        listener.onManualPaymentRecorded(applicationEvent(false));

        verify(invoiceGenerationService, never()).generateFromPayment(any(Payment.class));
        verify(emailService, never()).sendInvoiceIssuedEmail(any(), any(), any(), any(), any(), any(), any());
        verify(notificationService).createNotification(
                eq(APPLICANT_SEQ), eq(NotificationType.MANUAL_PAYMENT_CONFIRMED_APPLICANT),
                anyString(), anyString(), anyString(), anyLong());
        verify(notificationService, never()).createNotification(
                anyLong(), eq(NotificationType.INVOICE_ISSUED_APPLICANT),
                any(), any(), any(), any());
    }

    // ── AC-R2: 영수증 발행 실패 → 이메일/알림 스킵, 결제 보존, audit FAILED ──
    @Test
    @DisplayName("AC-R2: Invoice 발행 실패 → audit FAILED + 이메일·알림 스킵")
    void shouldRecordAuditFailedWhenInvoiceGenerationFails() {
        Payment payment = mock(Payment.class);
        when(paymentRepository.findById(PAYMENT_SEQ)).thenReturn(Optional.of(payment));
        when(invoiceGenerationService.generateFromPayment(payment))
                .thenThrow(new RuntimeException("PDF render failed"));

        listener.onManualPaymentRecorded(applicationEvent(true));

        verify(emailService, never()).sendInvoiceIssuedEmail(any(), any(), any(), any(), any(), any(), any());
        verify(notificationService, never()).createNotification(
                anyLong(), eq(NotificationType.INVOICE_ISSUED_APPLICANT),
                any(), any(), any(), any());
        // audit FAILED 기록
        verify(auditLogService).log(any(), any(), any(),
                eq(AuditAction.INVOICE_AUTO_GENERATED_FROM_MANUAL_PAYMENT),
                any(), any(), any(),
                org.mockito.ArgumentMatchers.contains("FAILED"),
                any(), any(), any(), any(), any(), any(), any());
    }

    // ── AC-R1 — D5=B: SMTP 발송 실패 시 결제·invoice 는 보존, audit FAILED ──
    @Test
    @DisplayName("AC-R1 / D5=B: 이메일 발송 실패 → audit FAILED, listener 자체는 정상 종료")
    void shouldSwallowEmailFailureAndRecordAuditFailed() {
        stubBaseDependencies();
        doThrow(new RuntimeException("SMTP timeout"))
                .when(emailService).sendInvoiceIssuedEmail(any(), any(), any(), any(), any(), any(), any());

        // listener 가 예외를 던지지 않아야 함.
        listener.onManualPaymentRecorded(applicationEvent(true));

        // FAILED audit 기록 (이메일 실패 사유 포함)
        verify(auditLogService).log(any(), any(), any(),
                eq(AuditAction.INVOICE_AUTO_GENERATED_FROM_MANUAL_PAYMENT),
                any(), any(), any(),
                org.mockito.ArgumentMatchers.contains("invoice email delivery failed"),
                any(), any(), any(), any(), any(), any(), any());
    }
}

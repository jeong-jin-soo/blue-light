package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.PaymentConfirmRequest;
import com.bluelight.backend.api.admin.dto.PaymentResponse;
import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.api.concierge.ApplicationStatusChangedEvent;
import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.api.invoice.InvoiceGenerationService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.domain.payment.Payment;
import com.bluelight.backend.domain.payment.PaymentRepository;
import com.bluelight.backend.domain.payment.PaymentStatus;
import com.bluelight.backend.domain.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Admin 결제 관리 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminPaymentService {

    private final ApplicationRepository applicationRepository;
    private final PaymentRepository paymentRepository;
    private final EmailService emailService;
    /**
     * ★ Phase 1 PR#7: Application → ConciergeRequest 상태 동기화용 이벤트 발행<br>
     * ★ PR4: 결제 확인 → LEW 알림 (인앱+이메일) 트리거용 {@link PaymentConfirmedEvent} 발행
     */
    private final ApplicationEventPublisher eventPublisher;
    /** invoice-spec §5: 결제 확인 직후 자동 영수증 발행 */
    private final InvoiceGenerationService invoiceGenerationService;
    /** invoice-spec §5: 자동 발행 실패 시 감사 로그 기록 */
    private final AuditLogService auditLogService;

    /**
     * Confirm offline payment (creates Payment record + changes status to PAID)
     */
    @Transactional
    public PaymentResponse confirmPayment(Long applicationSeq, PaymentConfirmRequest request) {
        Application application = findApplicationOrThrow(applicationSeq);

        if (application.getStatus() != ApplicationStatus.PENDING_PAYMENT) {
            throw new BusinessException(
                    "Payment can only be confirmed for applications with PENDING_PAYMENT status",
                    HttpStatus.BAD_REQUEST,
                    "INVALID_STATUS_FOR_PAYMENT"
            );
        }

        // Normalize blank fields
        String transactionId = request.getTransactionId();
        if (transactionId != null && transactionId.isBlank()) transactionId = null;

        // Create payment record
        Payment payment = Payment.builder()
                .application(application)
                .transactionId(transactionId)
                .amount(application.getQuoteAmount())
                .paymentMethod(request.getPaymentMethod() != null ? request.getPaymentMethod() : "BANK_TRANSFER")
                .status(PaymentStatus.SUCCESS)
                .build();

        Payment savedPayment = paymentRepository.save(payment);

        // Update application status
        ApplicationStatus previousStatus = application.getStatus();
        application.markAsPaid();

        // ★ Phase 1 PR#7: ConciergeRequest 자동 동기화 트리거
        eventPublisher.publishEvent(new ApplicationStatusChangedEvent(
            applicationSeq,
            application.getViaConciergeRequestSeq(),
            previousStatus,
            application.getStatus()));

        log.info("Payment confirmed: applicationSeq={}, paymentSeq={}, amount={}",
                applicationSeq, savedPayment.getPaymentSeq(), savedPayment.getAmount());

        // 신청자에게 결제 확인 이메일 발송
        User applicant = application.getUser();
        emailService.sendPaymentConfirmEmail(
                applicant.getEmail(),
                applicant.getFirstName() + " " + applicant.getLastName(),
                applicationSeq,
                application.getAddress(),
                savedPayment.getAmount());

        // ★ PR4: 배정된 LEW 에게 인앱 알림 + 이메일 발송은 LewPaymentNotificationListener 가
        // AFTER_COMMIT 단계에서 처리한다. 여기서는 이벤트만 발행 — 알림 발송 실패가
        // 결제 확정 트랜잭션을 롤백하지 않도록 보장하기 위함.
        eventPublisher.publishEvent(new PaymentConfirmedEvent(
                applicationSeq,
                savedPayment.getPaymentSeq(),
                savedPayment.getAmount(),
                LocalDateTime.now()));

        // invoice-spec §5: 결제 확인 직후 자동 영수증 발행.
        // 실패해도 결제 트랜잭션은 롤백하지 않음 — 관리자가 /regenerate 로 수동 복구.
        try {
            invoiceGenerationService.generateFromPayment(savedPayment, application);
        } catch (Exception e) {
            log.error("Invoice generation failed for payment {}: {}",
                    savedPayment.getPaymentSeq(), e.getMessage(), e);
            try {
                auditLogService.log(
                        null, null, null,
                        AuditAction.INVOICE_GENERATION_FAILED,
                        AuditCategory.APPLICATION,
                        "Payment",
                        String.valueOf(savedPayment.getPaymentSeq()),
                        "Invoice auto-generation failed: " + e.getMessage(),
                        null, null, null, null, null, null, null);
            } catch (Exception auditEx) {
                log.warn("Failed to record INVOICE_GENERATION_FAILED audit: {}", auditEx.getMessage());
            }
        }

        return PaymentResponse.from(savedPayment);
    }

    /**
     * Get payment history for an application
     */
    public List<PaymentResponse> getPayments(Long applicationSeq) {
        // Verify application exists
        findApplicationOrThrow(applicationSeq);

        return paymentRepository.findByApplicationApplicationSeq(applicationSeq)
                .stream()
                .map(PaymentResponse::from)
                .toList();
    }

    private Application findApplicationOrThrow(Long applicationSeq) {
        return applicationRepository.findById(applicationSeq)
                .orElseThrow(() -> new BusinessException(
                        "Application not found",
                        HttpStatus.NOT_FOUND,
                        "APPLICATION_NOT_FOUND"
                ));
    }
}

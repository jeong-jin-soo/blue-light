package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.PaymentConfirmRequest;
import com.bluelight.backend.api.admin.dto.PaymentResponse;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.domain.payment.Payment;
import com.bluelight.backend.domain.payment.PaymentRepository;
import com.bluelight.backend.domain.payment.PaymentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        application.markAsPaid();

        log.info("Payment confirmed: applicationSeq={}, paymentSeq={}, amount={}",
                applicationSeq, savedPayment.getPaymentSeq(), savedPayment.getAmount());

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

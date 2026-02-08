package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.*;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.domain.payment.Payment;
import com.bluelight.backend.domain.payment.PaymentRepository;
import com.bluelight.backend.domain.payment.PaymentStatus;
import com.bluelight.backend.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Admin application management service
 * - View all applications, change status, confirm payment, complete & issue licence
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminApplicationService {

    private final ApplicationRepository applicationRepository;
    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;

    /**
     * Get admin dashboard summary
     */
    public AdminDashboardResponse getDashboardSummary() {
        long totalApplications = applicationRepository.count();
        long pendingPayment = applicationRepository.countByStatus(ApplicationStatus.PENDING_PAYMENT);
        long paid = applicationRepository.countByStatus(ApplicationStatus.PAID);
        long inProgress = applicationRepository.countByStatus(ApplicationStatus.IN_PROGRESS);
        long completed = applicationRepository.countByStatus(ApplicationStatus.COMPLETED);
        long totalUsers = userRepository.count();

        return AdminDashboardResponse.builder()
                .totalApplications(totalApplications)
                .pendingPayment(pendingPayment)
                .paid(paid)
                .inProgress(inProgress)
                .completed(completed)
                .totalUsers(totalUsers)
                .build();
    }

    /**
     * Get all applications (paginated, optional status filter and search)
     */
    public Page<AdminApplicationResponse> getAllApplications(ApplicationStatus status, String search, Pageable pageable) {
        Page<Application> page;
        boolean hasSearch = search != null && !search.trim().isEmpty();

        if (hasSearch && status != null) {
            page = applicationRepository.searchByKeywordAndStatus(search.trim(), status, pageable);
        } else if (hasSearch) {
            page = applicationRepository.searchByKeyword(search.trim(), pageable);
        } else if (status != null) {
            page = applicationRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
        } else {
            page = applicationRepository.findAllByOrderByCreatedAtDesc(pageable);
        }
        return page.map(AdminApplicationResponse::from);
    }

    /**
     * Get application detail (admin view)
     */
    public AdminApplicationResponse getApplication(Long applicationSeq) {
        Application application = findApplicationOrThrow(applicationSeq);
        return AdminApplicationResponse.from(application);
    }

    /**
     * Update application status
     */
    @Transactional
    public AdminApplicationResponse updateStatus(Long applicationSeq, UpdateStatusRequest request) {
        Application application = findApplicationOrThrow(applicationSeq);

        // Validate status transition
        validateStatusTransition(application.getStatus(), request.getStatus());

        application.changeStatus(request.getStatus());
        log.info("Application status updated: applicationSeq={}, oldStatus={}, newStatus={}",
                applicationSeq, application.getStatus(), request.getStatus());

        return AdminApplicationResponse.from(application);
    }

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
     * Complete application and issue licence
     */
    @Transactional
    public AdminApplicationResponse completeApplication(Long applicationSeq, CompleteApplicationRequest request) {
        Application application = findApplicationOrThrow(applicationSeq);

        if (application.getStatus() != ApplicationStatus.IN_PROGRESS) {
            throw new BusinessException(
                    "Only applications with IN_PROGRESS status can be completed",
                    HttpStatus.BAD_REQUEST,
                    "INVALID_STATUS_FOR_COMPLETION"
            );
        }

        application.issueLicense(request.getLicenseNumber(), request.getLicenseExpiryDate());

        log.info("Application completed: applicationSeq={}, licenseNumber={}, expiryDate={}",
                applicationSeq, request.getLicenseNumber(), request.getLicenseExpiryDate());

        return AdminApplicationResponse.from(application);
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

    // --- Private helpers ---

    private Application findApplicationOrThrow(Long applicationSeq) {
        return applicationRepository.findById(applicationSeq)
                .orElseThrow(() -> new BusinessException(
                        "Application not found",
                        HttpStatus.NOT_FOUND,
                        "APPLICATION_NOT_FOUND"
                ));
    }

    private void validateStatusTransition(ApplicationStatus current, ApplicationStatus target) {
        boolean valid = switch (target) {
            case PAID -> current == ApplicationStatus.PENDING_PAYMENT;
            case IN_PROGRESS -> current == ApplicationStatus.PAID;
            case COMPLETED -> current == ApplicationStatus.IN_PROGRESS;
            case EXPIRED -> true; // can expire from any state
            case PENDING_PAYMENT -> false; // cannot revert to pending
        };

        if (!valid) {
            throw new BusinessException(
                    "Invalid status transition: " + current + " -> " + target,
                    HttpStatus.BAD_REQUEST,
                    "INVALID_STATUS_TRANSITION"
            );
        }
    }
}

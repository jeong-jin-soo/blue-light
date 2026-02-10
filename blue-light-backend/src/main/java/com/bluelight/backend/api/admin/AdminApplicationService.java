package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.*;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.domain.payment.Payment;
import com.bluelight.backend.domain.payment.PaymentRepository;
import com.bluelight.backend.domain.payment.PaymentStatus;
import com.bluelight.backend.domain.price.MasterPrice;
import com.bluelight.backend.domain.price.MasterPriceRepository;
import com.bluelight.backend.domain.setting.SystemSetting;
import com.bluelight.backend.domain.setting.SystemSettingRepository;
import com.bluelight.backend.domain.user.ApprovalStatus;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserRole;
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
    private final SystemSettingRepository systemSettingRepository;
    private final MasterPriceRepository masterPriceRepository;

    /**
     * Get admin dashboard summary
     */
    public AdminDashboardResponse getDashboardSummary() {
        long totalApplications = applicationRepository.count();
        long pendingReview = applicationRepository.countByStatus(ApplicationStatus.PENDING_REVIEW);
        long revisionRequested = applicationRepository.countByStatus(ApplicationStatus.REVISION_REQUESTED);
        long pendingPayment = applicationRepository.countByStatus(ApplicationStatus.PENDING_PAYMENT);
        long paid = applicationRepository.countByStatus(ApplicationStatus.PAID);
        long inProgress = applicationRepository.countByStatus(ApplicationStatus.IN_PROGRESS);
        long completed = applicationRepository.countByStatus(ApplicationStatus.COMPLETED);
        long expired = applicationRepository.countByStatus(ApplicationStatus.EXPIRED);
        long totalUsers = userRepository.count();

        long unassigned = applicationRepository.countByAssignedLewIsNull();

        return AdminDashboardResponse.builder()
                .totalApplications(totalApplications)
                .pendingReview(pendingReview)
                .revisionRequested(revisionRequested)
                .pendingPayment(pendingPayment)
                .paid(paid)
                .inProgress(inProgress)
                .completed(completed)
                .expired(expired)
                .totalUsers(totalUsers)
                .unassigned(unassigned)
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

    /**
     * LEW 보완 요청
     */
    @Transactional
    public AdminApplicationResponse requestRevision(Long applicationSeq, RevisionRequestDto request) {
        Application application = findApplicationOrThrow(applicationSeq);

        if (application.getStatus() != ApplicationStatus.PENDING_REVIEW) {
            throw new BusinessException(
                    "Revision can only be requested for applications in PENDING_REVIEW status",
                    HttpStatus.BAD_REQUEST, "INVALID_STATUS_FOR_REVISION");
        }

        application.requestRevision(request.getComment());
        log.info("Revision requested: applicationSeq={}", applicationSeq);

        return AdminApplicationResponse.from(application);
    }

    /**
     * LEW 검토 승인 → 결제 요청
     */
    @Transactional
    public AdminApplicationResponse approveForPayment(Long applicationSeq) {
        Application application = findApplicationOrThrow(applicationSeq);

        if (application.getStatus() != ApplicationStatus.PENDING_REVIEW) {
            throw new BusinessException(
                    "Only applications in PENDING_REVIEW status can be approved for payment",
                    HttpStatus.BAD_REQUEST, "INVALID_STATUS_FOR_APPROVAL");
        }

        application.approveForPayment();
        log.info("Application approved for payment: applicationSeq={}", applicationSeq);

        return AdminApplicationResponse.from(application);
    }

    // --- LEW Assignment ---

    /**
     * 신청에 LEW 할당
     */
    @Transactional
    public AdminApplicationResponse assignLew(Long applicationSeq, AssignLewRequest request) {
        Application application = findApplicationOrThrow(applicationSeq);

        User lew = userRepository.findById(request.getLewUserSeq())
                .orElseThrow(() -> new BusinessException(
                        "LEW user not found", HttpStatus.NOT_FOUND, "LEW_NOT_FOUND"));

        if (lew.getRole() != UserRole.LEW) {
            throw new BusinessException(
                    "User is not a LEW", HttpStatus.BAD_REQUEST, "NOT_LEW_USER");
        }
        if (!lew.isApproved()) {
            throw new BusinessException(
                    "LEW is not approved", HttpStatus.BAD_REQUEST, "LEW_NOT_APPROVED");
        }

        application.assignLew(lew);
        log.info("LEW assigned: applicationSeq={}, lewSeq={}", applicationSeq, lew.getUserSeq());

        return AdminApplicationResponse.from(application);
    }

    /**
     * 신청에서 LEW 할당 해제
     */
    @Transactional
    public AdminApplicationResponse unassignLew(Long applicationSeq) {
        Application application = findApplicationOrThrow(applicationSeq);
        application.unassignLew();
        log.info("LEW unassigned: applicationSeq={}", applicationSeq);
        return AdminApplicationResponse.from(application);
    }

    /**
     * 할당 가능한 LEW 목록 조회 (APPROVED 상태)
     */
    public List<LewSummaryResponse> getAvailableLews() {
        return userRepository.findByRoleAndApprovedStatus(UserRole.LEW, ApprovalStatus.APPROVED)
                .stream()
                .map(LewSummaryResponse::from)
                .toList();
    }

    // --- Price Management ---

    /**
     * 모든 가격 티어 조회 (kVA 최소값 오름차순)
     */
    public List<AdminPriceResponse> getAllPrices() {
        return masterPriceRepository.findAll().stream()
                .sorted((a, b) -> a.getKvaMin().compareTo(b.getKvaMin()))
                .map(AdminPriceResponse::from)
                .toList();
    }

    /**
     * 가격 티어 수정
     */
    @Transactional
    public AdminPriceResponse updatePrice(Long priceSeq, UpdatePriceRequest request) {
        MasterPrice masterPrice = masterPriceRepository.findById(priceSeq)
                .orElseThrow(() -> new BusinessException(
                        "Price tier not found",
                        HttpStatus.NOT_FOUND,
                        "PRICE_TIER_NOT_FOUND"
                ));

        // 가격 수정
        masterPrice.updatePrice(request.getPrice());

        // kVA 범위 및 설명 수정
        if (request.getKvaMin() != null && request.getKvaMax() != null) {
            if (request.getKvaMin() > request.getKvaMax()) {
                throw new BusinessException(
                        "kVA min cannot be greater than kVA max",
                        HttpStatus.BAD_REQUEST,
                        "INVALID_KVA_RANGE"
                );
            }
            masterPrice.updateKvaRange(
                    request.getKvaMin(),
                    request.getKvaMax(),
                    request.getDescription()
            );
        } else if (request.getDescription() != null) {
            masterPrice.updateKvaRange(
                    masterPrice.getKvaMin(),
                    masterPrice.getKvaMax(),
                    request.getDescription()
            );
        }

        // 활성화 상태 수정
        if (request.getIsActive() != null) {
            masterPrice.setActive(request.getIsActive());
        }

        log.info("Price tier updated: priceSeq={}, price={}, kvaMin={}, kvaMax={}, isActive={}",
                priceSeq, request.getPrice(), masterPrice.getKvaMin(),
                masterPrice.getKvaMax(), masterPrice.getIsActive());

        return AdminPriceResponse.from(masterPrice);
    }

    // --- System Settings ---

    /**
     * 시스템 설정 조회
     */
    public java.util.Map<String, String> getSettings() {
        java.util.Map<String, String> settings = new java.util.HashMap<>();
        systemSettingRepository.findAll().forEach(s ->
                settings.put(s.getSettingKey(), s.getSettingValue()));
        return settings;
    }

    /**
     * 시스템 설정 변경
     */
    @Transactional
    public java.util.Map<String, String> updateSettings(java.util.Map<String, String> updates, Long updatedBy) {
        updates.forEach((key, value) -> {
            SystemSetting setting = systemSettingRepository.findById(key)
                    .orElseThrow(() -> new BusinessException(
                            "Setting not found: " + key, HttpStatus.NOT_FOUND, "SETTING_NOT_FOUND"));
            setting.updateValue(value, updatedBy);
            log.info("Setting updated: key={}, value={}, by={}", key, value, updatedBy);
        });
        return getSettings();
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
            case PENDING_REVIEW -> current == ApplicationStatus.REVISION_REQUESTED;
            case REVISION_REQUESTED -> current == ApplicationStatus.PENDING_REVIEW;
            case PENDING_PAYMENT -> current == ApplicationStatus.PENDING_REVIEW;
            case PAID -> current == ApplicationStatus.PENDING_PAYMENT;
            case IN_PROGRESS -> current == ApplicationStatus.PAID;
            case COMPLETED -> current == ApplicationStatus.IN_PROGRESS;
            case EXPIRED -> true; // can expire from any state
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

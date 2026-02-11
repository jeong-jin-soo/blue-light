package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.*;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.application.*;
import com.bluelight.backend.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin 신청 관리 핵심 서비스
 * - 대시보드, 신청 목록/상세, 상태 변경, 보완 요청, 승인, 완료
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminApplicationService {

    private final ApplicationRepository applicationRepository;
    private final UserRepository userRepository;

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

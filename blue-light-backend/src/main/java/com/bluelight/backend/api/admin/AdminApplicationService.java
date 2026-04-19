package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.*;
import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.application.*;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

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
    private final EmailService emailService;

    /**
     * Get admin dashboard summary (역할별 범위 분리)
     */
    public AdminDashboardResponse getDashboardSummary(Long userSeq, String role) {
        if ("ROLE_LEW".equals(role)) {
            return getLewDashboardSummary(userSeq);
        }

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
     * LEW 전용 대시보드: 자기 배정 신청서만 집계
     */
    private AdminDashboardResponse getLewDashboardSummary(Long lewSeq) {
        long totalApplications = applicationRepository.countByAssignedLewUserSeq(lewSeq);
        long pendingReview = applicationRepository.countByAssignedLewUserSeqAndStatus(lewSeq, ApplicationStatus.PENDING_REVIEW);
        long revisionRequested = applicationRepository.countByAssignedLewUserSeqAndStatus(lewSeq, ApplicationStatus.REVISION_REQUESTED);
        long pendingPayment = applicationRepository.countByAssignedLewUserSeqAndStatus(lewSeq, ApplicationStatus.PENDING_PAYMENT);
        long paid = applicationRepository.countByAssignedLewUserSeqAndStatus(lewSeq, ApplicationStatus.PAID);
        long inProgress = applicationRepository.countByAssignedLewUserSeqAndStatus(lewSeq, ApplicationStatus.IN_PROGRESS);
        long completed = applicationRepository.countByAssignedLewUserSeqAndStatus(lewSeq, ApplicationStatus.COMPLETED);
        long expired = applicationRepository.countByAssignedLewUserSeqAndStatus(lewSeq, ApplicationStatus.EXPIRED);

        return AdminDashboardResponse.builder()
                .totalApplications(totalApplications)
                .pendingReview(pendingReview)
                .revisionRequested(revisionRequested)
                .pendingPayment(pendingPayment)
                .paid(paid)
                .inProgress(inProgress)
                .completed(completed)
                .expired(expired)
                .totalUsers(0)
                .unassigned(0)
                .build();
    }

    /**
     * Get all applications (paginated, optional status filter and search)
     * LEW는 자신에게 배정된 신청서만, Admin/SystemAdmin은 전체
     */
    public Page<AdminApplicationResponse> getAllApplications(
            ApplicationStatus status, KvaStatus kvaStatus, String search, Pageable pageable,
            Long userSeq, String role) {
        Page<Application> page;
        boolean hasSearch = search != null && !search.trim().isEmpty();
        boolean isLew = "ROLE_LEW".equals(role);

        // Phase 5 PR#3 — kvaStatus 필터가 들어오면 Specification 경로로 통합
        // (기존 필터 조합과 직교). kvaStatus 미지정 시에는 기존 전용 쿼리 경로 유지.
        if (kvaStatus != null) {
            Long lewSeqFilter = isLew ? userSeq : null;
            Pageable sorted = pageable.getSort().isSorted()
                    ? pageable
                    : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                            Sort.by(Sort.Direction.DESC, "createdAt"));
            page = applicationRepository.findAll(
                    buildSpec(status, kvaStatus, hasSearch ? search.trim() : null, lewSeqFilter),
                    sorted);
        } else if (isLew) {
            page = getLewApplications(status, search, hasSearch, userSeq, pageable);
        } else {
            page = getAdminApplications(status, search, hasSearch, pageable);
        }
        return page.map(AdminApplicationResponse::from);
    }

    /**
     * Phase 5 PR#3 — AC-P3: kvaStatus 필터를 포함한 복합 Specification.
     * status / kvaStatus / keyword / assignedLew (LEW 역할) 를 AND 로 조합한다.
     */
    private Specification<Application> buildSpec(
            ApplicationStatus status, KvaStatus kvaStatus, String keyword, Long lewSeqFilter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (kvaStatus != null) {
                predicates.add(cb.equal(root.get("kvaStatus"), kvaStatus));
            }
            if (lewSeqFilter != null) {
                predicates.add(cb.equal(root.get("assignedLew").get("userSeq"), lewSeqFilter));
            }
            if (keyword != null && !keyword.isEmpty()) {
                String like = "%" + keyword.toLowerCase() + "%";
                var userJoin = root.join("user");
                Predicate byAddress = cb.like(cb.lower(root.get("address")), like);
                Predicate byName = cb.like(
                        cb.lower(cb.concat(cb.concat(userJoin.get("firstName"), " "),
                                userJoin.get("lastName"))),
                        like);
                Predicate byEmail = cb.like(cb.lower(userJoin.get("email")), like);
                Predicate byId = cb.like(
                        root.get("applicationSeq").as(String.class),
                        "%" + keyword + "%");
                predicates.add(cb.or(byAddress, byName, byEmail, byId));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * LEW 전용: 배정된 신청서만 조회
     */
    private Page<Application> getLewApplications(
            ApplicationStatus status, String search, boolean hasSearch, Long lewSeq, Pageable pageable) {
        if (hasSearch && status != null) {
            return applicationRepository.searchByKeywordAndStatusAndAssignedLew(search.trim(), status, lewSeq, pageable);
        } else if (hasSearch) {
            return applicationRepository.searchByKeywordAndAssignedLew(search.trim(), lewSeq, pageable);
        } else if (status != null) {
            return applicationRepository.findByAssignedLewUserSeqAndStatusOrderByCreatedAtDesc(lewSeq, status, pageable);
        } else {
            return applicationRepository.findByAssignedLewUserSeqOrderByCreatedAtDesc(lewSeq, pageable);
        }
    }

    /**
     * Admin/SystemAdmin: 전체 신청서 조회
     */
    private Page<Application> getAdminApplications(
            ApplicationStatus status, String search, boolean hasSearch, Pageable pageable) {
        if (hasSearch && status != null) {
            return applicationRepository.searchByKeywordAndStatus(search.trim(), status, pageable);
        } else if (hasSearch) {
            return applicationRepository.searchByKeyword(search.trim(), pageable);
        } else if (status != null) {
            return applicationRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
        } else {
            return applicationRepository.findAllByOrderByCreatedAtDesc(pageable);
        }
    }

    /**
     * Get application detail (admin view)
     * LEW는 자신에게 배정된 신청서만 조회 가능
     */
    public AdminApplicationResponse getApplication(Long applicationSeq, Long userSeq, String role) {
        Application application = findApplicationOrThrow(applicationSeq);

        // LEW → 배정된 신청서만 접근 허용
        if ("ROLE_LEW".equals(role)) {
            Long assignedLewSeq = application.getAssignedLew() != null
                    ? application.getAssignedLew().getUserSeq() : null;
            if (assignedLewSeq == null || !assignedLewSeq.equals(userSeq)) {
                throw new BusinessException("Access denied", HttpStatus.FORBIDDEN, "ACCESS_DENIED");
            }
        }

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

        // 신청자에게 면허 발급 완료 이메일 발송
        User applicant = application.getUser();
        emailService.sendLicenseIssuedEmail(
                applicant.getEmail(),
                applicant.getFirstName() + " " + applicant.getLastName(),
                applicationSeq,
                application.getAddress(),
                request.getLicenseNumber(),
                request.getLicenseExpiryDate());

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

        // 신청자에게 보완 요청 이메일 발송
        User applicant = application.getUser();
        emailService.sendRevisionRequestEmail(
                applicant.getEmail(),
                applicant.getFirstName() + " " + applicant.getLastName(),
                applicationSeq,
                application.getAddress(),
                request.getComment());

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

        // Phase 5 B-1: kVA 가 UNKNOWN 인 신청은 결제 단계 진입 차단.
        // security-review §1.2 — 실제 코드 경로는 `/approve` 이며, 여기에 가드 배치.
        if (application.getKvaStatus() == KvaStatus.UNKNOWN) {
            throw new BusinessException(
                    "Payment will be enabled after LEW confirms the kVA",
                    HttpStatus.BAD_REQUEST, "KVA_NOT_CONFIRMED");
        }

        application.approveForPayment();
        log.info("Application approved for payment: applicationSeq={}", applicationSeq);

        // 신청자에게 결제 요청 이메일 발송
        User applicant = application.getUser();
        emailService.sendPaymentRequestEmail(
                applicant.getEmail(),
                applicant.getFirstName() + " " + applicant.getLastName(),
                applicationSeq,
                application.getAddress(),
                application.getQuoteAmount());

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

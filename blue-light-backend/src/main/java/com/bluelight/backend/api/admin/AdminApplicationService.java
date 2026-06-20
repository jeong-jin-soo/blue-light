package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.*;
import com.bluelight.backend.api.application.EmaRejectedEvent;
import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.api.concierge.ApplicationStatusChangedEvent;
import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.application.*;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.file.FileType;
import com.bluelight.backend.domain.file.FileRepository;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    /** ★ Phase 1 PR#7: Application → ConciergeRequest 상태 동기화용 이벤트 발행 */
    private final ApplicationEventPublisher eventPublisher;
    /**
     * 완료/EMA 게이트 공용 — LICENSE_PDF·EMA_ACK 첨부 검증용 (PR4 게이트 + EMA 추적 공유, 단일 주입).
     */
    private final FileRepository fileRepository;
    /** ── EMA 제출 추적 (ema-submission-tracking-spec.md) — 감사/설정조회 ── */
    private final AuditLogService auditLogService;
    private final EmaSubmissionSettings emaSubmissionSettings;
    /** ── SLD 전환 발급/정산 게이트 (sld-lew-conversion-fee-spec.md §8, D-5/D-6) ── */
    private final com.bluelight.backend.domain.application.SldRequestRepository sldRequestRepository;
    private final com.bluelight.backend.domain.kva.KvaAdjustmentRepository kvaAdjustmentRepository;

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
     *
     * <p>★ 코드 부채 P0 (PR-T8 H-2 단일화) — LEW cross-tenant 가드는 컨트롤러
     * {@code @PreAuthorize("@appSec.isAssignedLew(...)")} 가 단일 책임. 서비스 내 가드 제거.</p>
     */
    public AdminApplicationResponse getApplication(Long applicationSeq) {
        Application application = findApplicationOrThrow(applicationSeq);
        return AdminApplicationResponse.from(application);
    }

    /**
     * Update application status
     *
     * <p>★ 코드 부채 P0 — LEW 가드는 컨트롤러 SpEL 단일화.</p>
     */
    @Transactional
    public AdminApplicationResponse updateStatus(Long applicationSeq, UpdateStatusRequest request) {
        Application application = findApplicationOrThrow(applicationSeq);

        // Validate status transition
        validateStatusTransition(application.getStatus(), request.getStatus());

        // PR4 (D-2): PAID → IN_PROGRESS 진입은 LEW 최종본(LOA_FINAL) 업로드 완료가 전제.
        if (application.getStatus() == ApplicationStatus.PAID
                && request.getStatus() == ApplicationStatus.IN_PROGRESS
                && application.getLoaStage() != LoaStage.FINAL_UPLOADED) {
            throw new BusinessException(
                    "The final LoA must be uploaded by the LEW before starting EMA submission.",
                    HttpStatus.CONFLICT, "LOA_FINAL_NOT_UPLOADED");
        }

        ApplicationStatus previousStatus = application.getStatus();
        application.changeStatus(request.getStatus());
        log.info("Application status updated: applicationSeq={}, oldStatus={}, newStatus={}",
                applicationSeq, previousStatus, request.getStatus());

        // ★ Phase 1 PR#7: ConciergeRequest 자동 동기화 트리거
        eventPublisher.publishEvent(new ApplicationStatusChangedEvent(
            applicationSeq,
            application.getViaConciergeRequestSeq(),
            previousStatus,
            application.getStatus()));

        return AdminApplicationResponse.from(application);
    }

    /**
     * Complete application and issue licence
     *
     * <p>★ 코드 부채 P0 — LEW 가드는 컨트롤러 SpEL 단일화.</p>
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

        // ── EMA 종료 게이트 (ema-submission-tracking-spec.md §4.2 하이브리드 — PR4 LICENSE_PDF 단독 게이트 대체) ──
        // issueLicense() 호출 전에 두 전제를 검증한다:
        //   ① EMA 제출 상태가 APPROVED 여야 한다(승인 없이는 발급 불가).
        //   ② LICENSE_PDF 첨부가 존재해야 한다("번호만 입력·파일 누락" 공백 차단 — PR4 게이트 흡수).
        // grandfathered(backfill) 건도 APPROVED 라 ①은 통과하지만 ②는 그대로 적용된다(§11 R3).
        // LoA 게이트 D-1(결제)·D-2(IN_PROGRESS 진입)는 별개 관심사로 각 메서드에서 유지된다.
        if (application.getEmaSubmissionStatus() != EmaSubmissionStatus.APPROVED) {
            throw new BusinessException(
                    "EMA submission must be APPROVED before completion",
                    HttpStatus.BAD_REQUEST, "EMA_NOT_APPROVED");
        }
        if (fileRepository.findByApplicationApplicationSeqAndFileType(
                applicationSeq, FileType.LICENSE_PDF).isEmpty()) {
            throw new BusinessException(
                    "License PDF must be uploaded before completion",
                    HttpStatus.BAD_REQUEST, "LICENSE_PDF_MISSING");
        }

        // ── D-5 발급 게이트 (sld-lew-conversion-fee-spec.md §8): REQUEST_LEW 면 SLD 가 CONFIRMED 여야 한다.
        //    (LEW 작성 SLD 요금을 청구한 이상, SLD 없이 발급되는 공백을 차단.)
        if (application.getSldOption() == com.bluelight.backend.domain.application.SldOption.REQUEST_LEW) {
            boolean sldConfirmed = sldRequestRepository
                    .findByApplicationApplicationSeq(applicationSeq)
                    .map(sr -> sr.getStatus() == com.bluelight.backend.domain.application.SldRequestStatus.CONFIRMED)
                    .orElse(false);
            if (!sldConfirmed) {
                throw new BusinessException(
                        "The LEW-created SLD must be confirmed before completion",
                        HttpStatus.CONFLICT, "SLD_NOT_CONFIRMED");
            }
        }

        // ── D-6 정산 게이트 (sld-lew-conversion-fee-spec.md §8): 미정산(PENDING) SLD 보충 청구가 있으면 차단.
        if (kvaAdjustmentRepository.existsByApplication_ApplicationSeqAndAdjustmentTypeAndAdminPaymentAdjustment(
                applicationSeq,
                com.bluelight.backend.domain.kva.AdjustmentType.SLD_ADDED,
                com.bluelight.backend.domain.kva.AdminPaymentAdjustment.PENDING)) {
            throw new BusinessException(
                    "The additional SLD fee must be settled before completion",
                    HttpStatus.CONFLICT, "SLD_FEE_NOT_SETTLED");
        }

        ApplicationStatus previousStatus = application.getStatus();
        application.issueLicense(request.getLicenseNumber(), request.getLicenseExpiryDate());

        log.info("Application completed: applicationSeq={}, licenseNumber={}, expiryDate={}",
                applicationSeq, request.getLicenseNumber(), request.getLicenseExpiryDate());

        // ★ Phase 1 PR#7: ConciergeRequest 자동 동기화 트리거 (IN_PROGRESS → COMPLETED)
        eventPublisher.publishEvent(new ApplicationStatusChangedEvent(
            applicationSeq,
            application.getViaConciergeRequestSeq(),
            previousStatus,
            application.getStatus()));

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
     *
     * <p>★ 코드 부채 P0 — LEW 가드는 컨트롤러 SpEL 단일화.</p>
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
     *
     * <p>★ 코드 부채 P0 — LEW 가드는 컨트롤러 SpEL 단일화.</p>
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

        // 결제·LoA 병렬 분리 (사용자 결정 2026-06-18, payment-gateway-marketplace-spec.md §1.5):
        // 결제 요청은 kVA 확정만 전제로 조기화한다(현금 조기 유입). LoA 최종본 게이트는
        // 작업개시(PAID→IN_PROGRESS, D-2 `LOA_FINAL_NOT_UPLOADED`)에만 유지되어 "LoA 없이 작업 시작" 은
        // 여전히 차단된다. LoA 교환은 결제와 무관하게 병렬 진행(LoaService 에 결제 게이트 없음).
        application.approveForPayment();
        log.info("Application approved for payment: applicationSeq={}", applicationSeq);

        // 신청자에게 결제 요청 알림 (A-17 인앱+이메일, 오케스트레이터). LEW 경로와 통일.
        com.bluelight.backend.api.notification.PaymentRequestNotifier.dispatch(eventPublisher, application);

        return AdminApplicationResponse.from(application);
    }

    // --- Private helpers ---

    /**
     * ★ 코드 부채 P0 — 이전 PR-T8 의 {@code ensureLewCanAccess} 는 컨트롤러
     * {@code @PreAuthorize("@appSec.isAssignedLew(...)")} 로 이관 완료. 본 서비스에서 제거.
     * SpEL 빈: {@link com.bluelight.backend.common.security.AppSecurity}.
     */
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

    // ============================================================
    // EMA ELISE 제출 추적 — 전이 서비스 (ema-submission-tracking-spec.md §3, T1~T10)
    // ------------------------------------------------------------
    // 도메인 상태 기계는 Application 엔티티가 소유한다. 본 서비스는:
    //   ① App.status==IN_PROGRESS 게이트(NG3) ② 접수번호/queryNote 필수 검증
    //   ③ 제출 계열(T1/T3/T10)의 EMA_ACK 첨부 검증(ema.ack.required 설정 분기)
    //   ④ actor userSeq + actor role(LEW 본인 vs ADMIN 대행) 감사 기록(§3.2)
    // 을 담당한다. 권한 SpEL(LEW 본인 + ADMIN 대행)은 컨트롤러(PR-E2) 책임.
    // 잘못된 from→to 전이는 도메인 메서드가 BusinessException("INVALID_EMA_TRANSITION").
    // ============================================================

    /**
     * T1: NOT_SUBMITTED → SUBMITTED. ELISE 제출 사실 기록.
     *
     * @param applicationSeq 신청 ID
     * @param emaReferenceNo ELISE 접수번호 (필수)
     * @param actorSeq       호출자 userSeq (제출 actor 로 영속 보관)
     * @param role           호출자 권한 (e.g. {@code ROLE_LEW}, {@code ROLE_ADMIN}) — 감사 actor role
     */
    @Transactional
    public EmaSubmissionResponse markEmaSubmitted(Long applicationSeq, String emaReferenceNo,
                                                     Long actorSeq, String role) {
        Application application = findApplicationOrThrow(applicationSeq);
        requireInProgress(application);
        requireNonBlank(emaReferenceNo, "EMA reference number is required", "EMA_REFERENCE_REQUIRED");
        // 제출 계열(T1) — ack.required=true 면 EMA_ACK 첨부 필수
        verifyAckAttachmentIfRequired(applicationSeq);

        application.markEmaSubmitted(emaReferenceNo.trim(), actorSeq);
        auditEmaTransition(applicationSeq, AuditAction.EMA_SUBMITTED, actorSeq, role, application,
                "EMA marked submitted", Map.of("emaReferenceNo", emaReferenceNo.trim()));
        return buildEmaResponse(application);
    }

    /**
     * T2/T4: SUBMITTED/RESUBMITTED → QUERY_RAISED. EMA 질의 기록.
     */
    @Transactional
    public EmaSubmissionResponse raiseEmaQuery(Long applicationSeq, String queryNote,
                                                  Long actorSeq, String role) {
        Application application = findApplicationOrThrow(applicationSeq);
        requireInProgress(application);
        requireNonBlank(queryNote, "EMA query note is required", "EMA_QUERY_NOTE_REQUIRED");

        application.raiseEmaQuery(queryNote.trim());
        auditEmaTransition(applicationSeq, AuditAction.EMA_QUERY_RAISED, actorSeq, role, application,
                "EMA query raised", Map.of("queryNote", queryNote.trim()));
        return buildEmaResponse(application);
    }

    /**
     * T3/T10: QUERY_RAISED/REJECTED → RESUBMITTED. 보완 후 재제출.
     * 직전 결정·사유·복원 슬롯은 도메인 메서드가 클리어한다(허점#4).
     *
     * @param emaReferenceNo 갱신된 접수번호 (선택 — null/blank 면 기존 값 유지)
     */
    @Transactional
    public EmaSubmissionResponse resubmitEma(Long applicationSeq, String emaReferenceNo,
                                                Long actorSeq, String role) {
        Application application = findApplicationOrThrow(applicationSeq);
        requireInProgress(application);
        // 제출 계열(T3/T10) — ack.required=true 면 EMA_ACK 재첨부 필수
        verifyAckAttachmentIfRequired(applicationSeq);

        String trimmedRef = (emaReferenceNo != null && !emaReferenceNo.isBlank())
                ? emaReferenceNo.trim() : null;
        application.resubmitEma(trimmedRef, actorSeq);
        auditEmaTransition(applicationSeq, AuditAction.EMA_RESUBMITTED, actorSeq, role, application,
                "EMA resubmitted", mapOfNullable("emaReferenceNo", trimmedRef));
        return buildEmaResponse(application);
    }

    /**
     * T5/T6: SUBMITTED/RESUBMITTED → APPROVED. EMA 승인 표기(발급과 분리 — 완료 게이트는 별도).
     * 직전 from 상태를 복원 슬롯에 저장한다(허점#1, 도메인 메서드).
     */
    @Transactional
    public EmaSubmissionResponse approveEma(Long applicationSeq, Long actorSeq, String role) {
        Application application = findApplicationOrThrow(applicationSeq);
        requireInProgress(application);

        application.approveEma();
        auditEmaTransition(applicationSeq, AuditAction.EMA_APPROVED, actorSeq, role, application,
                "EMA approved", Map.of());
        return buildEmaResponse(application);
    }

    /**
     * T7: SUBMITTED/RESUBMITTED → REJECTED. EMA 반려(종착 아님 — T10 재진입 가능).
     * App.status 는 IN_PROGRESS 유지.
     *
     * @param reason 반려 사유 (선택)
     */
    @Transactional
    public EmaSubmissionResponse rejectEma(Long applicationSeq, String reason,
                                              Long actorSeq, String role) {
        Application application = findApplicationOrThrow(applicationSeq);
        requireInProgress(application);

        String trimmedReason = (reason != null && !reason.isBlank()) ? reason.trim() : null;
        application.rejectEma(trimmedReason);
        auditEmaTransition(applicationSeq, AuditAction.EMA_REJECTED, actorSeq, role, application,
                "EMA rejected", mapOfNullable("reason", trimmedReason));

        // PR-E5: 담당 LEW 에게 반려 통지 (AFTER_COMMIT 리스너에서 IN_APP 발행 — 선례 패턴).
        // 신청자에게는 비노출(US-C1). 배정 LEW 없으면 lewSeq=null → 리스너가 skip.
        Long assignedLewSeq = application.getAssignedLew() != null
                ? application.getAssignedLew().getUserSeq() : null;
        eventPublisher.publishEvent(new EmaRejectedEvent(applicationSeq, assignedLewSeq, trimmedReason));

        return buildEmaResponse(application);
    }

    /**
     * T8: SUBMITTED/QUERY_RAISED/RESUBMITTED → WITHDRAWN. EMA 철회(종착).
     */
    @Transactional
    public EmaSubmissionResponse withdrawEma(Long applicationSeq, Long actorSeq, String role) {
        Application application = findApplicationOrThrow(applicationSeq);
        requireInProgress(application);

        application.withdrawEma();
        auditEmaTransition(applicationSeq, AuditAction.EMA_WITHDRAWN, actorSeq, role, application,
                "EMA withdrawn", Map.of());
        return buildEmaResponse(application);
    }

    /**
     * T9: APPROVED/WITHDRAWN → 직전 상태 복원. ADMIN/SYSTEM_ADMIN 전용(컨트롤러 SpEL 로 LEW 제외).
     * 복원 슬롯 null 이면 SUBMITTED 폴백(grandfathered 등, 허점#1, 도메인 메서드).
     */
    @Transactional
    public EmaSubmissionResponse revertEmaDecision(Long applicationSeq, Long actorSeq, String role) {
        Application application = findApplicationOrThrow(applicationSeq);
        requireInProgress(application);

        EmaSubmissionStatus before = application.getEmaSubmissionStatus();
        application.revertEmaDecision();
        auditEmaTransition(applicationSeq, AuditAction.EMA_DECISION_REVERTED, actorSeq, role, application,
                "EMA decision reverted",
                Map.of("revertedFrom", before.name(),
                       "restoredTo", application.getEmaSubmissionStatus().name()));
        return buildEmaResponse(application);
    }

    // ── EMA 전이 공통 헬퍼 ───────────────────────────────────────

    /** App.status==IN_PROGRESS 게이트(NG3). COMPLETED/EXPIRED 등에서는 EMA 전이 불가. */
    private void requireInProgress(Application application) {
        if (application.getStatus() != ApplicationStatus.IN_PROGRESS) {
            throw new BusinessException(
                    "EMA transitions are only allowed while the application is IN_PROGRESS (current: "
                            + application.getStatus() + ")",
                    HttpStatus.BAD_REQUEST, "EMA_NOT_IN_PROGRESS");
        }
    }

    private void requireNonBlank(String value, String message, String code) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(message, HttpStatus.BAD_REQUEST, code);
        }
    }

    /**
     * 제출 계열 전이(T1/T3/T10)에서 {@code ema.ack.required=true} 면 EMA_ACK 첨부 존재를 검증한다
     * (설정 우선 원칙 — 플래그는 {@link EmaSubmissionSettings} 로 system_settings 에서 조회).
     * 미첨부 시 {@code BusinessException(BAD_REQUEST, "EMA_ACK_REQUIRED")}.
     */
    private void verifyAckAttachmentIfRequired(Long applicationSeq) {
        if (!emaSubmissionSettings.isAckRequired()) {
            return; // soft 도입 — 첨부 권장하되 미첨부도 전이 허용
        }
        boolean present = !fileRepository
                .findByApplicationApplicationSeqAndFileType(applicationSeq, FileType.EMA_ACK)
                .isEmpty();
        if (!present) {
            throw new BusinessException(
                    "EMA acknowledgement (EMA_ACK) attachment is required",
                    HttpStatus.BAD_REQUEST, "EMA_ACK_REQUIRED");
        }
    }

    /**
     * EMA 전이 감사 기록. actor userSeq + actor role 을 detail 에 명시해 LEW 본인/ADMIN 대행을
     * 사후 구분 가능하게 한다(§3.2). 비동기 REQUIRES_NEW 라 트랜잭션 롤백과 무관하게 기록된다.
     */
    private void auditEmaTransition(Long applicationSeq, AuditAction action, Long actorSeq, String role,
                                    Application application, String description,
                                    Map<String, Object> extra) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("actorRole", role);
        detail.put("emaSubmissionStatus", application.getEmaSubmissionStatus() != null
                ? application.getEmaSubmissionStatus().name() : null);
        if (extra != null) {
            detail.putAll(extra);
        }
        auditLogService.logAsync(
                actorSeq, action, AuditCategory.APPLICATION,
                "Application", String.valueOf(applicationSeq),
                description, null, detail,
                null, null, "POST",
                "/api/admin/applications/" + applicationSeq + "/ema", 200);
    }

    /** value 가 null 이면 빈 맵, 아니면 {key:value} 단일 엔트리 맵(불변). */
    private Map<String, Object> mapOfNullable(String key, Object value) {
        return value == null ? Map.of() : Map.of(key, value);
    }

    /**
     * EMA 제출 추적 조회 (GET). 전이 없이 현재 상태 + 파일 존재 여부 + 설정값을 묶어 반환.
     * 폴링/탭 갱신용 — 권한 가드는 컨트롤러 SpEL(LEW 본인 + ADMIN 대행).
     */
    public EmaSubmissionResponse getEmaSubmission(Long applicationSeq) {
        Application application = findApplicationOrThrow(applicationSeq);
        return buildEmaResponse(application);
    }

    /**
     * {@link EmaSubmissionResponse} 조립. 파일 존재 여부(EMA_ACK/LICENSE_PDF)·ack.required 설정·
     * 제출 actor 표시 이름을 계산해 DTO 정적 팩토리에 전달한다(설정 우선 — ack.required 는 설정 조회).
     */
    private EmaSubmissionResponse buildEmaResponse(Application application) {
        Long appSeq = application.getApplicationSeq();
        boolean ackPresent = !fileRepository
                .findByApplicationApplicationSeqAndFileType(appSeq, FileType.EMA_ACK).isEmpty();
        boolean licensePdfPresent = !fileRepository
                .findByApplicationApplicationSeqAndFileType(appSeq, FileType.LICENSE_PDF).isEmpty();
        boolean ackRequired = emaSubmissionSettings.isAckRequired();
        String submittedByName = resolveUserName(application.getEmaSubmittedByUserSeq());
        return EmaSubmissionResponse.of(application, ackPresent, licensePdfPresent, ackRequired, submittedByName);
    }

    /** userSeq → "FirstName LastName" 표시 이름 (미상이면 null). */
    private String resolveUserName(Long userSeq) {
        if (userSeq == null) {
            return null;
        }
        return userRepository.findById(userSeq)
                .map(u -> (u.getFirstName() + " " + u.getLastName()).trim())
                .orElse(null);
    }
}

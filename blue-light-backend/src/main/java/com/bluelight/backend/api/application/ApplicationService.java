package com.bluelight.backend.api.application;

import com.bluelight.backend.api.admin.dto.PaymentResponse;
import com.bluelight.backend.api.application.dto.ApplicationResponse;
import com.bluelight.backend.api.application.dto.ApplicationSummaryResponse;
import com.bluelight.backend.api.application.dto.CompanyInfoRequest;
import com.bluelight.backend.api.application.dto.CreateApplicationRequest;
import com.bluelight.backend.api.application.dto.UpdateApplicationRequest;
import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.common.util.OwnershipValidator;
import com.bluelight.backend.api.application.dto.CreateSldRequestDto;
import com.bluelight.backend.api.application.dto.SldRequestResponse;
import com.bluelight.backend.api.application.dto.UpdateSldRequestDto;
import com.bluelight.backend.domain.application.*;
import com.bluelight.backend.domain.file.FileEntity;
import com.bluelight.backend.domain.file.FileRepository;
import com.bluelight.backend.domain.file.FileType;
import com.bluelight.backend.domain.payment.PaymentRepository;
import com.bluelight.backend.domain.price.MasterPrice;
import com.bluelight.backend.domain.price.MasterPriceRepository;
import com.bluelight.backend.domain.user.ApprovalStatus;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Application service for applicants
 * - Create (new/renewal), list, detail, summary, payment history
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ApplicationService {

    private final ApplicationRepository applicationRepository;
    private final SldRequestRepository sldRequestRepository;
    private final MasterPriceRepository masterPriceRepository;
    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final FileRepository fileRepository;
    private final AuditLogService auditLogService;

    /**
     * Create a new licence application (NEW or RENEWAL)
     */
    @Transactional(rollbackFor = Exception.class)
    public ApplicationResponse createApplication(Long userSeq, CreateApplicationRequest request) {
        // Find user
        User user = userRepository.findById(userSeq)
                .orElseThrow(() -> new BusinessException("User not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        // Phase 2 PR#3 — 법인 JIT 회사 정보 처리 (AC-J1~J6)
        // 같은 @Transactional 안에서 User 업데이트 + Application insert가 함께 커밋된다.
        applyCorporateJitCompanyInfo(user, request);

        // Phase 5 — "I don't know" 분기.
        // security-review §6: kvaStatus=UNKNOWN 이면 악의적 selectedKva 값을 무시하고 45 로 강제.
        // 이 강제 덮어쓰기는 가격 계산(findByKva) 진입 전에 수행해야 안전.
        boolean kvaUnknown = Boolean.TRUE.equals(request.getKvaUnknown());
        if (kvaUnknown) {
            request.setSelectedKva(45);
        }

        // Calculate price from kVA
        MasterPrice masterPrice = masterPriceRepository.findByKva(request.getSelectedKva())
                .orElseThrow(() -> new BusinessException(
                        "No price tier found for " + request.getSelectedKva() + " kVA",
                        HttpStatus.BAD_REQUEST,
                        "PRICE_TIER_NOT_FOUND"
                ));

        // Parse SLD option early (needed for fee calculation)
        SldOption sldOption = SldOption.SELF_UPLOAD;
        if ("REQUEST_LEW".equals(request.getSldOption())) {
            sldOption = SldOption.REQUEST_LEW;
        }

        // SLD fee: only when REQUEST_LEW
        BigDecimal sldFee = (sldOption == SldOption.REQUEST_LEW)
                ? masterPrice.getSldPrice() : null;

        // Determine application type
        ApplicationType appType = ApplicationType.NEW;
        if ("RENEWAL".equals(request.getApplicationType())) {
            appType = ApplicationType.RENEWAL;
        }

        Application originalApp = null;
        String existingLicenceNo = null;
        String renewalReferenceNo = request.getRenewalReferenceNo();
        LocalDate existingExpiryDate = null;
        Integer renewalPeriodMonths = null;
        BigDecimal emaFee = null;

        // Licence period (applicable to all types)
        if (request.getRenewalPeriodMonths() != null) {
            renewalPeriodMonths = request.getRenewalPeriodMonths();
            if (renewalPeriodMonths != 3 && renewalPeriodMonths != 12) {
                throw new BusinessException(
                        "Licence period must be 3 or 12 months",
                        HttpStatus.BAD_REQUEST, "INVALID_RENEWAL_PERIOD");
            }
            emaFee = calculateEmaFee(appType, renewalPeriodMonths);
        }

        // Calculate total: New License vs Renewal 다른 가격 적용
        BigDecimal tierPrice = (appType == ApplicationType.RENEWAL)
                ? masterPrice.getRenewalPrice()
                : masterPrice.getPrice();
        BigDecimal quoteAmount = tierPrice;
        if (sldFee != null) {
            quoteAmount = quoteAmount.add(sldFee);
        }
        if (emaFee != null) {
            quoteAmount = quoteAmount.add(emaFee);
        }

        if (appType == ApplicationType.RENEWAL) {
            // Renewal must have a licence period
            if (renewalPeriodMonths == null) {
                throw new BusinessException(
                        "Licence period is required for renewal",
                        HttpStatus.BAD_REQUEST, "INVALID_RENEWAL_PERIOD");
            }

            // Renewal reference number is required
            if (renewalReferenceNo == null || renewalReferenceNo.isBlank()) {
                throw new BusinessException(
                        "Renewal reference number is required",
                        HttpStatus.BAD_REQUEST, "RENEWAL_REF_REQUIRED");
            }

            // Link to original application if provided
            if (request.getOriginalApplicationSeq() != null) {
                originalApp = applicationRepository.findById(request.getOriginalApplicationSeq())
                        .orElseThrow(() -> new BusinessException(
                                "Original application not found",
                                HttpStatus.NOT_FOUND, "ORIGINAL_APP_NOT_FOUND"));

                // Verify ownership
                OwnershipValidator.validateOwner(originalApp.getUser().getUserSeq(), userSeq);

                // 원본 신청서가 COMPLETED 또는 EXPIRED 상태인지 검증
                if (originalApp.getStatus() != ApplicationStatus.COMPLETED
                        && originalApp.getStatus() != ApplicationStatus.EXPIRED) {
                    throw new BusinessException(
                            "Original application must be completed or expired for renewal",
                            HttpStatus.BAD_REQUEST, "ORIGINAL_APP_NOT_ELIGIBLE");
                }

                // Auto-fill from original
                existingLicenceNo = originalApp.getLicenseNumber();
                existingExpiryDate = originalApp.getLicenseExpiryDate();
            } else {
                // Manual entry
                existingLicenceNo = request.getExistingLicenceNo();
                if (request.getExistingExpiryDate() != null && !request.getExistingExpiryDate().isBlank()) {
                    existingExpiryDate = LocalDate.parse(request.getExistingExpiryDate());
                }
            }
        }

        // Create application
        Application application = Application.builder()
                .user(user)
                .address(request.getAddress())
                .postalCode(request.getPostalCode())
                .buildingType(request.getBuildingType())
                .selectedKva(request.getSelectedKva())
                .quoteAmount(quoteAmount)
                .sldFee(sldFee)
                .spAccountNo(request.getSpAccountNo())
                .sldOption(sldOption)
                .applicationType(appType)
                .applicantType(request.getApplicantType())
                .originalApplication(originalApp)
                .existingLicenceNo(existingLicenceNo)
                .renewalReferenceNo(renewalReferenceNo)
                .existingExpiryDate(existingExpiryDate)
                .renewalPeriodMonths(renewalPeriodMonths)
                .emaFee(emaFee)
                // Phase 5: kVA 상태 (UNKNOWN 이면 kvaSource=NULL, 아니면 USER_INPUT)
                .kvaStatus(kvaUnknown
                        ? com.bluelight.backend.domain.application.KvaStatus.UNKNOWN
                        : com.bluelight.backend.domain.application.KvaStatus.CONFIRMED)
                .kvaSource(kvaUnknown
                        ? null
                        : com.bluelight.backend.domain.application.KvaSource.USER_INPUT)
                .build();

        // 승인된 LEW가 1명이면 자동 할당
        List<User> approvedLews = userRepository.findByRoleAndApprovedStatus(
                UserRole.LEW, ApprovalStatus.APPROVED);
        if (approvedLews.size() == 1) {
            application.assignLew(approvedLews.get(0));
            log.info("LEW auto-assigned: lewSeq={}", approvedLews.get(0).getUserSeq());
        }

        Application saved = applicationRepository.save(application);
        log.info("Application created: seq={}, type={}, userSeq={}, kva={}, amount={}, sldFee={}, sldOption={}",
                saved.getApplicationSeq(), appType, userSeq,
                request.getSelectedKva(), quoteAmount, sldFee, sldOption);

        // SLD 요청 시 자동으로 SldRequest 생성
        if (sldOption == SldOption.REQUEST_LEW) {
            SldRequest sldRequest = SldRequest.builder()
                    .application(saved)
                    .applicantNote(null)
                    .build();
            sldRequestRepository.save(sldRequest);
            log.info("SLD request auto-created: applicationSeq={}", saved.getApplicationSeq());
        }

        return ApplicationResponse.from(saved);
    }

    /**
     * Update and resubmit application (after revision request)
     */
    @Transactional
    public ApplicationResponse updateApplication(Long userSeq, Long applicationSeq,
                                                  UpdateApplicationRequest request) {
        Application application = applicationRepository.findById(applicationSeq)
                .orElseThrow(() -> new BusinessException(
                        "Application not found", HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND"));

        // Verify ownership
        OwnershipValidator.validateOwner(application.getUser().getUserSeq(), userSeq);

        // Only allow editing in REVISION_REQUESTED status
        if (application.getStatus() != ApplicationStatus.REVISION_REQUESTED) {
            throw new BusinessException(
                    "Application can only be edited when revision is requested",
                    HttpStatus.BAD_REQUEST, "INVALID_STATUS_FOR_EDIT");
        }

        // Recalculate price if kVA changed (+ SLD fee + EMA fee)
        MasterPrice masterPrice = masterPriceRepository.findByKva(request.getSelectedKva())
                .orElseThrow(() -> new BusinessException(
                        "No price tier found for " + request.getSelectedKva() + " kVA",
                        HttpStatus.BAD_REQUEST, "PRICE_TIER_NOT_FOUND"));

        // SLD fee: only when REQUEST_LEW
        BigDecimal sldFee = (application.getSldOption() == SldOption.REQUEST_LEW)
                ? masterPrice.getSldPrice() : null;

        // Determine current EMA fee (may be updated below)
        BigDecimal currentEmaFee = application.getEmaFee();

        // Licence period 변경 처리 (모든 타입)
        if (request.getRenewalPeriodMonths() != null) {
            int months = request.getRenewalPeriodMonths();
            if (months != 3 && months != 12) {
                throw new BusinessException(
                        "Licence period must be 3 or 12 months",
                        HttpStatus.BAD_REQUEST, "INVALID_RENEWAL_PERIOD");
            }
            currentEmaFee = calculateEmaFee(application.getApplicationType(), months);
            application.updateRenewalPeriod(months, currentEmaFee);
        }

        // Calculate total: New License vs Renewal 다른 가격 적용
        BigDecimal tierPrice = (application.getApplicationType() == ApplicationType.RENEWAL)
                ? masterPrice.getRenewalPrice()
                : masterPrice.getPrice();
        BigDecimal quoteAmount = tierPrice;
        if (sldFee != null) {
            quoteAmount = quoteAmount.add(sldFee);
        }
        if (currentEmaFee != null) {
            quoteAmount = quoteAmount.add(currentEmaFee);
        }

        application.updateDetails(
                request.getAddress(), request.getPostalCode(),
                request.getBuildingType(), request.getSelectedKva(),
                quoteAmount, sldFee
        );

        // SP Account No 수정
        if (request.getSpAccountNo() != null) {
            application.updateSpAccountNo(request.getSpAccountNo());
        }

        // Auto-transition status back to PENDING_REVIEW
        application.resubmit();

        log.info("Application updated and resubmitted: applicationSeq={}, userSeq={}",
                applicationSeq, userSeq);

        return ApplicationResponse.from(application);
    }

    /**
     * Get all applications for the authenticated user
     */
    public List<ApplicationResponse> getMyApplications(Long userSeq) {
        return applicationRepository.findByUserUserSeqOrderByCreatedAtDesc(userSeq)
                .stream()
                .map(ApplicationResponse::from)
                .toList();
    }

    /**
     * Get a single application detail
     */
    public ApplicationResponse getMyApplication(Long userSeq, Long applicationSeq) {
        Application application = applicationRepository.findById(applicationSeq)
                .orElseThrow(() -> new BusinessException(
                        "Application not found",
                        HttpStatus.NOT_FOUND,
                        "APPLICATION_NOT_FOUND"
                ));

        // Verify ownership
        OwnershipValidator.validateOwner(application.getUser().getUserSeq(), userSeq);

        return ApplicationResponse.from(application);
    }

    /**
     * Get application summary for dashboard
     */
    public ApplicationSummaryResponse getMyApplicationSummary(Long userSeq) {
        List<Application> applications = applicationRepository.findByUserUserSeq(userSeq);

        long total = applications.size();
        long pendingReview = applications.stream()
                .filter(a -> a.getStatus() == ApplicationStatus.PENDING_REVIEW
                        || a.getStatus() == ApplicationStatus.REVISION_REQUESTED)
                .count();
        long pendingPayment = applications.stream()
                .filter(a -> a.getStatus() == ApplicationStatus.PENDING_PAYMENT)
                .count();
        long inProgress = applications.stream()
                .filter(a -> a.getStatus() == ApplicationStatus.PAID || a.getStatus() == ApplicationStatus.IN_PROGRESS)
                .count();
        long completed = applications.stream()
                .filter(a -> a.getStatus() == ApplicationStatus.COMPLETED)
                .count();

        return ApplicationSummaryResponse.builder()
                .total(total)
                .pendingReview(pendingReview)
                .pendingPayment(pendingPayment)
                .inProgress(inProgress)
                .completed(completed)
                .build();
    }

    /**
     * Get payment history for an application (verifies ownership)
     */
    public List<PaymentResponse> getApplicationPayments(Long userSeq, Long applicationSeq) {
        Application application = applicationRepository.findById(applicationSeq)
                .orElseThrow(() -> new BusinessException(
                        "Application not found",
                        HttpStatus.NOT_FOUND,
                        "APPLICATION_NOT_FOUND"
                ));

        // Verify ownership
        OwnershipValidator.validateOwner(application.getUser().getUserSeq(), userSeq);

        return paymentRepository.findByApplicationApplicationSeq(applicationSeq)
                .stream()
                .map(PaymentResponse::from)
                .toList();
    }

    /**
     * Get completed applications for the user (갱신 시 원본 선택용)
     */
    public List<ApplicationResponse> getCompletedApplications(Long userSeq) {
        return applicationRepository.findByUserUserSeqAndStatusOrderByCreatedAtDesc(
                        userSeq, ApplicationStatus.COMPLETED)
                .stream()
                .map(ApplicationResponse::from)
                .toList();
    }

    // ── SLD Request (신청자용) ────────────────────

    /**
     * SLD 요청 생성 (신청서 상세 페이지에서 후속 요청 시)
     */
    @Transactional
    public SldRequestResponse createSldRequest(Long userSeq, Long applicationSeq, CreateSldRequestDto dto) {
        Application application = applicationRepository.findById(applicationSeq)
                .orElseThrow(() -> new BusinessException(
                        "Application not found", HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND"));

        OwnershipValidator.validateOwner(application.getUser().getUserSeq(), userSeq);

        // 중복 체크
        if (sldRequestRepository.findByApplicationApplicationSeq(applicationSeq).isPresent()) {
            throw new BusinessException(
                    "SLD request already exists for this application",
                    HttpStatus.CONFLICT, "SLD_REQUEST_ALREADY_EXISTS");
        }

        SldRequest sldRequest = SldRequest.builder()
                .application(application)
                .applicantNote(dto.getNote())
                .build();
        sldRequestRepository.save(sldRequest);

        log.info("SLD request created: applicationSeq={}, userSeq={}", applicationSeq, userSeq);
        return SldRequestResponse.from(sldRequest);
    }

    /**
     * SLD 요청 조회 (신청자용)
     */
    public SldRequestResponse getSldRequest(Long userSeq, Long applicationSeq) {
        Application application = applicationRepository.findById(applicationSeq)
                .orElseThrow(() -> new BusinessException(
                        "Application not found", HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND"));

        OwnershipValidator.validateOwner(application.getUser().getUserSeq(), userSeq);

        return sldRequestRepository.findByApplicationApplicationSeq(applicationSeq)
                .map(SldRequestResponse::from)
                .orElse(null);
    }

    /**
     * SLD 요청 수정 — 신청자가 메모 + 스케치 파일 업데이트
     */
    @Transactional
    public SldRequestResponse updateSldRequest(Long userSeq, Long applicationSeq, UpdateSldRequestDto dto) {
        Application application = applicationRepository.findById(applicationSeq)
                .orElseThrow(() -> new BusinessException(
                        "Application not found", HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND"));

        OwnershipValidator.validateOwner(application.getUser().getUserSeq(), userSeq);

        SldRequest sldRequest = sldRequestRepository.findByApplicationApplicationSeq(applicationSeq)
                .orElseThrow(() -> new BusinessException(
                        "SLD request not found", HttpStatus.NOT_FOUND, "SLD_REQUEST_NOT_FOUND"));

        // REQUESTED 상태에서만 수정 가능
        if (sldRequest.getStatus() != SldRequestStatus.REQUESTED) {
            throw new BusinessException(
                    "SLD request can only be updated in REQUESTED status",
                    HttpStatus.BAD_REQUEST, "INVALID_SLD_REQUEST_STATUS");
        }

        // 스케치 파일 검증 (있는 경우)
        Long sketchFileSeq = dto.getSketchFileSeq();
        if (sketchFileSeq != null) {
            FileEntity sketchFile = fileRepository.findById(sketchFileSeq)
                    .orElseThrow(() -> new BusinessException(
                            "Sketch file not found", HttpStatus.NOT_FOUND, "FILE_NOT_FOUND"));

            // 해당 신청에 속하는 파일인지 확인
            if (!sketchFile.getApplication().getApplicationSeq().equals(applicationSeq)) {
                throw new BusinessException(
                        "File does not belong to this application",
                        HttpStatus.BAD_REQUEST, "FILE_NOT_OWNED");
            }

            // SKETCH_SLD 타입인지 확인
            if (sketchFile.getFileType() != FileType.SKETCH_SLD) {
                throw new BusinessException(
                        "File is not a sketch file",
                        HttpStatus.BAD_REQUEST, "INVALID_FILE_TYPE");
            }
        }

        sldRequest.updateApplicantDetails(dto.getNote(), sketchFileSeq);

        log.info("SLD request updated: applicationSeq={}, userSeq={}, hasSketch={}",
                applicationSeq, userSeq, sketchFileSeq != null);
        return SldRequestResponse.from(sldRequest);
    }

    /**
     * Phase 2 PR#3 — 법인 JIT 회사 정보 적용.
     *
     * 규칙:
     * - applicantType != CORPORATE 이면 no-op (INDIVIDUAL일 때 companyInfo 전송되어도 무시).
     * - CORPORATE이고 User에 companyName이 이미 있으면 companyInfo 없이도 통과 (모달 없이 제출 케이스).
     * - CORPORATE이고 User.companyName이 없는데 companyInfo도 누락이면 400 COMPANY_INFO_REQUIRED.
     * - companyInfo가 주어졌을 때 persistToProfile=true(default)면 User에 저장 + 감사 로그 2건
     *   (PROFILE_COMPANY_INFO_UPDATED + CORPORATE_INFO_CAPTURED_VIA_JIT).
     * - persistToProfile=false이면 User는 변경하지 않고 감사 로그 1건만 기록
     *   (CORPORATE_INFO_CAPTURED_VIA_JIT, metadata.persistToProfile=false).
     */
    private void applyCorporateJitCompanyInfo(User user, CreateApplicationRequest request) {
        if (request.getApplicantType() != ApplicantType.CORPORATE) {
            return;
        }

        CompanyInfoRequest info = request.getCompanyInfo();
        boolean userHasCompany = user.getCompanyName() != null && !user.getCompanyName().isBlank();

        if (info == null) {
            if (!userHasCompany) {
                throw new BusinessException(
                        "Company info is required for corporate applications",
                        HttpStatus.BAD_REQUEST, "COMPANY_INFO_REQUIRED");
            }
            // User에 이미 회사정보가 있음 → JIT 불필요, no-op
            return;
        }

        String companyName = info.getCompanyName() == null ? null : info.getCompanyName().trim();
        String uen = info.getUen() == null ? null : info.getUen().trim();
        String designation = info.getDesignation() == null ? null : info.getDesignation().trim();
        boolean persist = info.shouldPersistToProfile();

        Map<String, String> before = new LinkedHashMap<>();
        before.put("companyName", user.getCompanyName());
        before.put("uen", user.getUen());
        before.put("designation", user.getDesignation());

        Map<String, String> after = new LinkedHashMap<>();
        after.put("companyName", companyName);
        after.put("uen", uen);
        after.put("designation", designation);

        if (persist && !before.equals(after)) {
            user.updateCompanyInfo(companyName, uen, designation);
            // 기존 프로필 수정 경로와 동일한 감사 이벤트 (Phase 1 B-2와 일관)
            auditLogService.logAsync(
                    user.getUserSeq(),
                    AuditAction.PROFILE_COMPANY_INFO_UPDATED,
                    AuditCategory.DATA_PROTECTION,
                    "User", String.valueOf(user.getUserSeq()),
                    "Company information captured via JIT modal during application submission",
                    before, after,
                    null, null, "POST", "/api/applications", 201
            );
        }

        // JIT 경로 표식 감사 이벤트 (Security B-2: persistToProfile flag 반드시 기록)
        Map<String, Object> jitMetadata = new LinkedHashMap<>();
        jitMetadata.put("persistToProfile", persist);
        jitMetadata.put("companyName", companyName);
        jitMetadata.put("uen", uen);
        jitMetadata.put("designation", designation);

        auditLogService.logAsync(
                user.getUserSeq(),
                AuditAction.CORPORATE_INFO_CAPTURED_VIA_JIT,
                AuditCategory.APPLICATION,
                "User", String.valueOf(user.getUserSeq()),
                "Corporate company info captured via JIT modal",
                null, jitMetadata,
                null, null, "POST", "/api/applications", 201
        );
    }

    /**
     * EMA 수수료 계산
     * - 3개월=$50, 12개월=$100
     */
    private BigDecimal calculateEmaFee(ApplicationType type, int months) {
        if (months == 3) {
            return new BigDecimal("50.00");
        }
        return new BigDecimal("100.00");
    }
}

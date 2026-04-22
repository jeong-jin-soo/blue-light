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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
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
    private final ApplicationDeclarationLogRepository applicationDeclarationLogRepository;

    /** Declaration 문서 버전 상수 — 문구가 바뀌면 증가시켜 법적 증거 체인을 분리한다. */
    private static final String DECLARATION_DOCUMENT_VERSION = "2026-04-declaration-v1";

    /** JIT 스펙에 정의된 Declaration 3개 그룹 consent_type. */
    private static final String[] DECLARATION_CONSENT_TYPES = {
            "APPLICATION_DECLARATION_V1_GROUP1",
            "APPLICATION_DECLARATION_V1_GROUP2",
            "APPLICATION_DECLARATION_V1_GROUP3"
    };

    /**
     * Create a new licence application (NEW or RENEWAL).
     * <p>기존 호출부 호환을 위해 래퍼 시그니처(IP/UA 없음)를 함께 제공한다.
     */
    @Transactional(rollbackFor = Exception.class)
    public ApplicationResponse createApplication(Long userSeq, CreateApplicationRequest request) {
        return createApplication(userSeq, request, null, null);
    }

    /**
     * Create a new licence application (NEW or RENEWAL).
     * Declaration 로그를 append하기 위해 IP/UA를 함께 기록한다.
     */
    @Transactional(rollbackFor = Exception.class)
    public ApplicationResponse createApplication(Long userSeq, CreateApplicationRequest request,
                                                 String clientIp, String userAgent) {
        // Find user
        User user = userRepository.findById(userSeq)
                .orElseThrow(() -> new BusinessException("User not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        // Phase 2 PR#3 — 법인 JIT 회사 정보 처리 (AC-J1~J6)
        // 같은 @Transactional 안에서 User 업데이트 + Application insert가 함께 커밋된다.
        applyCorporateJitCompanyInfo(user, request);

        // Phase 5 — "I don't know" 분기.
        // security-review §6: kvaStatus=UNKNOWN 이면 악의적 selectedKva 값을 무시하고 최저 tier 로 강제.
        // 현재 master_prices 최저 tier 는 55 kVA (45는 tier 존재하지 않음 — Phase 5 배포 후 버그 발견).
        // 이 강제 덮어쓰기는 가격 계산(findByKva) 진입 전에 수행해야 안전.
        boolean kvaUnknown = Boolean.TRUE.equals(request.getKvaUnknown());
        if (kvaUnknown) {
            request.setSelectedKva(55);
        }

        // Calculate price from kVA
        MasterPrice masterPrice = masterPriceRepository.findByKva(request.getSelectedKva())
                .orElseThrow(() -> new BusinessException(
                        "No price tier found for " + request.getSelectedKva() + " kVA",
                        HttpStatus.BAD_REQUEST,
                        "PRICE_TIER_NOT_FOUND"
                ));

        // Parse SLD option early (needed for fee calculation).
        // P1.4: SUBMIT_WITHIN_3_MONTHS 지원 — 미지정/오타는 SELF_UPLOAD 기본.
        SldOption sldOption = parseSldOption(request.getSldOption());

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

                // ── P1.4: Renewal 변경 체크박스 서버 검증 (tester [MEDIUM]) ──
                // 플래그가 false인데 실제 값이 변경되면 악의적 조작 의심 → 400.
                Boolean companyChangedFlag = request.getRenewalCompanyNameChanged();
                if (Boolean.FALSE.equals(companyChangedFlag) || companyChangedFlag == null) {
                    String prevCompany = originalApp.getUser() != null ? originalApp.getUser().getCompanyName() : null;
                    String curCompany = user.getCompanyName();
                    if (prevCompany != null && curCompany != null
                            && !java.util.Objects.equals(prevCompany, curCompany)) {
                        throw new BusinessException(
                                "Company name has changed since the previous application. "
                                + "Please check 'Company name has changed' in the renewal section.",
                                HttpStatus.BAD_REQUEST, "RENEWAL_COMPANY_CHANGE_UNFLAGGED");
                    }
                }
                Boolean addressChangedFlag = request.getRenewalAddressChanged();
                if (Boolean.FALSE.equals(addressChangedFlag) || addressChangedFlag == null) {
                    String prevAddr = originalApp.getAddress();
                    String curAddr = request.getAddress();
                    if (prevAddr != null && curAddr != null && !prevAddr.equals(curAddr)) {
                        throw new BusinessException(
                                "Installation address has changed since the previous application. "
                                + "Please check 'Installation address has changed' in the renewal section.",
                                HttpStatus.BAD_REQUEST, "RENEWAL_ADDRESS_CHANGE_UNFLAGGED");
                    }
                }
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
                // ── P1.2: EMA ELISE 필드 전파 (nullable, JIT) ──
                .installationName(request.getInstallationName())
                .premisesType(request.getPremisesType())
                .isRentalPremises(request.getIsRentalPremises())
                .landlordEiLicenceNo(
                        Boolean.TRUE.equals(request.getIsRentalPremises())
                                ? request.getLandlordEiLicenceNo()
                                : null)
                .renewalCompanyNameChanged(request.getRenewalCompanyNameChanged())
                .renewalAddressChanged(request.getRenewalAddressChanged())
                .installationAddressBlock(request.getInstallationAddressBlock())
                .installationAddressUnit(request.getInstallationAddressUnit())
                .installationAddressStreet(request.getInstallationAddressStreet())
                .installationAddressBuilding(request.getInstallationAddressBuilding())
                .installationAddressPostalCode(request.getInstallationAddressPostalCode())
                .correspondenceAddressBlock(request.getCorrespondenceAddressBlock())
                .correspondenceAddressUnit(request.getCorrespondenceAddressUnit())
                .correspondenceAddressStreet(request.getCorrespondenceAddressStreet())
                .correspondenceAddressBuilding(request.getCorrespondenceAddressBuilding())
                .correspondenceAddressPostalCode(request.getCorrespondenceAddressPostalCode())
                .build();

        // 승인된 LEW 중 이 신청의 kVA 를 처리 가능한(lewGrade 세팅된) LEW 가 1명이면 자동 할당.
        // 주의: lewGrade==null 인 LEW 는 admin 의 수동 지정 드롭다운(/api/admin/lews)에서도 제외되므로
        //       auto-assign 에서도 동일하게 제외해야 두 경로의 "배정 가능한 LEW" 정의가 일치한다.
        Integer kva = request.getSelectedKva();
        List<User> eligibleLews = userRepository.findByRoleAndApprovedStatus(
                UserRole.LEW, ApprovalStatus.APPROVED).stream()
                .filter(lew -> kva != null && lew.canHandleKva(kva))
                .toList();
        if (eligibleLews.size() == 1) {
            application.assignLew(eligibleLews.get(0));
            log.info("LEW auto-assigned: lewSeq={}", eligibleLews.get(0).getUserSeq());
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

        // ── P1.2: Declaration 3개 그룹을 append-only 로그에 기록 ──
        // 신청서 제출은 법적 선언에 해당하므로 3개 그룹 각각 한 행씩 불변 기록.
        recordApplicationDeclarations(saved, user, request, clientIp, userAgent);

        return ApplicationResponse.from(saved);
    }

    /**
     * 신청 Submit 시 Declaration 3개 그룹(UX 스펙의 축약 버전)을 application_declaration_logs에 append.
     * 실패해도 신청 자체는 롤백하지 않고 경고만 남긴다 (법적 추적성과 사용자 경험 사이의 절충).
     */
    private void recordApplicationDeclarations(Application saved, User user,
                                               CreateApplicationRequest request,
                                               String clientIp, String userAgent) {
        String formHash = resolveFormHash(request);
        LocalDateTime now = LocalDateTime.now();
        for (String consentType : DECLARATION_CONSENT_TYPES) {
            try {
                ApplicationDeclarationLog logEntry = ApplicationDeclarationLog.builder()
                        .application(saved)
                        .user(user)
                        .consentType(consentType)
                        .documentVersion(DECLARATION_DOCUMENT_VERSION)
                        .formSnapshotHash(formHash)
                        .ipAddress(truncate(clientIp, 45))
                        .userAgent(truncate(userAgent, 500))
                        .declaredAt(now)
                        .build();
                applicationDeclarationLogRepository.save(logEntry);
            } catch (Exception e) {
                log.warn("Declaration log append failed: applicationSeq={}, consentType={}, err={}",
                        saved.getApplicationSeq(), consentType, e.getMessage());
            }
        }
    }

    /**
     * formSnapshotHash 결정. 클라이언트가 전송한 값이 있으면 그대로 쓰고,
     * 없으면 핵심 필드들을 직렬화해 SHA-256 계산. 둘 다 실패 시 빈 문자열.
     */
    private String resolveFormHash(CreateApplicationRequest request) {
        if (request.getFormSnapshotHash() != null && !request.getFormSnapshotHash().isBlank()) {
            return request.getFormSnapshotHash();
        }
        String serialized = String.join("|",
                String.valueOf(request.getApplicantType()),
                String.valueOf(request.getApplicationType()),
                String.valueOf(request.getSelectedKva()),
                String.valueOf(request.getSldOption()),
                String.valueOf(request.getPostalCode()),
                String.valueOf(request.getAddress()),
                String.valueOf(request.getInstallationName()),
                String.valueOf(request.getPremisesType()),
                String.valueOf(request.getIsRentalPremises()),
                String.valueOf(request.getRenewalCompanyNameChanged()),
                String.valueOf(request.getRenewalAddressChanged())
        );
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(serialized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * SLD 옵션 파싱 — 3-way enum. 미지정/오타는 SELF_UPLOAD 기본.
     * Tester 리포트 HIGH 버그 수정: 기존에는 REQUEST_LEW만 인식하고
     * SUBMIT_WITHIN_3_MONTHS 전송 시 SELF_UPLOAD로 잘못 저장됐다.
     */
    private SldOption parseSldOption(String raw) {
        if (raw == null) return SldOption.SELF_UPLOAD;
        try {
            return SldOption.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return SldOption.SELF_UPLOAD;
        }
    }

    /**
     * Concierge Manager가 대리 생성하는 Application (★ Kaki Concierge v1.5 Phase 1 PR#5 Stage A).
     * <p>
     * Owner = targetApplicant (Application.user). Actor(created_by)는 SecurityContext의
     * AuditorAware에 의해 Manager로 자동 세팅된다. {@code viaConciergeRequestSeq}는
     * {@code @Column(updatable=false)}라 INSERT 시점에 Builder로만 주입 가능.
     * <p>
     * 검증 로직은 {@link #createApplication}과 동일(JIT 회사 정보, kVA UNKNOWN 강제,
     * MasterPrice 조회, RENEWAL 전용 검증 등). JIT 회사 정보는 Manager의 것이 아닌
     * <b>target applicant</b>의 User 레코드에 적용된다.
     * <p>
     * ownership 검증(OwnershipValidator)은 {@code originalApplicationSeq}가 있을 때만 수행 —
     * 이때 "원본 Application 소유자"가 target applicant와 일치해야 함.
     *
     * @param targetApplicantSeq     대리 생성 대상 신청자 seq (Application.user가 될 대상)
     * @param conciergeRequestSeq    연결할 ConciergeRequest seq (via_concierge_request_seq에 기록)
     * @param request                신청서 본문 (기존 CreateApplicationRequest 재사용)
     */
    @Transactional(rollbackFor = Exception.class)
    public ApplicationResponse createOnBehalfOf(Long targetApplicantSeq,
                                                 Long conciergeRequestSeq,
                                                 CreateApplicationRequest request) {
        // Target applicant 조회 (Manager가 대리 소유자로 지정하는 대상)
        User user = userRepository.findById(targetApplicantSeq)
                .orElseThrow(() -> new BusinessException(
                        "Target applicant not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        // 법인 JIT (target applicant 기준)
        applyCorporateJitCompanyInfo(user, request);

        // kVA UNKNOWN 분기 (applicant 경로와 동일)
        boolean kvaUnknown = Boolean.TRUE.equals(request.getKvaUnknown());
        if (kvaUnknown) {
            request.setSelectedKva(55);
        }

        MasterPrice masterPrice = masterPriceRepository.findByKva(request.getSelectedKva())
                .orElseThrow(() -> new BusinessException(
                        "No price tier found for " + request.getSelectedKva() + " kVA",
                        HttpStatus.BAD_REQUEST, "PRICE_TIER_NOT_FOUND"));

        SldOption sldOption = parseSldOption(request.getSldOption());

        BigDecimal sldFee = (sldOption == SldOption.REQUEST_LEW)
                ? masterPrice.getSldPrice() : null;

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

        if (request.getRenewalPeriodMonths() != null) {
            renewalPeriodMonths = request.getRenewalPeriodMonths();
            if (renewalPeriodMonths != 3 && renewalPeriodMonths != 12) {
                throw new BusinessException(
                        "Licence period must be 3 or 12 months",
                        HttpStatus.BAD_REQUEST, "INVALID_RENEWAL_PERIOD");
            }
            emaFee = calculateEmaFee(appType, renewalPeriodMonths);
        }

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
            if (renewalPeriodMonths == null) {
                throw new BusinessException(
                        "Licence period is required for renewal",
                        HttpStatus.BAD_REQUEST, "INVALID_RENEWAL_PERIOD");
            }
            if (renewalReferenceNo == null || renewalReferenceNo.isBlank()) {
                throw new BusinessException(
                        "Renewal reference number is required",
                        HttpStatus.BAD_REQUEST, "RENEWAL_REF_REQUIRED");
            }

            if (request.getOriginalApplicationSeq() != null) {
                originalApp = applicationRepository.findById(request.getOriginalApplicationSeq())
                        .orElseThrow(() -> new BusinessException(
                                "Original application not found",
                                HttpStatus.NOT_FOUND, "ORIGINAL_APP_NOT_FOUND"));

                // 원본 소유자 == target applicant 여야 함 (Manager가 타인 원본을 끌어쓸 수 없음)
                OwnershipValidator.validateOwner(
                    originalApp.getUser().getUserSeq(), targetApplicantSeq);

                if (originalApp.getStatus() != ApplicationStatus.COMPLETED
                        && originalApp.getStatus() != ApplicationStatus.EXPIRED) {
                    throw new BusinessException(
                            "Original application must be completed or expired for renewal",
                            HttpStatus.BAD_REQUEST, "ORIGINAL_APP_NOT_ELIGIBLE");
                }

                existingLicenceNo = originalApp.getLicenseNumber();
                existingExpiryDate = originalApp.getLicenseExpiryDate();
            } else {
                existingLicenceNo = request.getExistingLicenceNo();
                if (request.getExistingExpiryDate() != null && !request.getExistingExpiryDate().isBlank()) {
                    existingExpiryDate = LocalDate.parse(request.getExistingExpiryDate());
                }
            }
        }

        // Application 빌드 — ★ PR#5 Stage A: viaConciergeRequestSeq 주입
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
                .kvaStatus(kvaUnknown
                        ? com.bluelight.backend.domain.application.KvaStatus.UNKNOWN
                        : com.bluelight.backend.domain.application.KvaStatus.CONFIRMED)
                .kvaSource(kvaUnknown
                        ? null
                        : com.bluelight.backend.domain.application.KvaSource.USER_INPUT)
                .viaConciergeRequestSeq(conciergeRequestSeq)
                // ── P1.4: Concierge 대리 생성 경로도 EMA 필드 전파 (tester [HIGH] 수정) ──
                .installationName(request.getInstallationName())
                .premisesType(request.getPremisesType())
                .isRentalPremises(request.getIsRentalPremises())
                .landlordEiLicenceNo(
                        Boolean.TRUE.equals(request.getIsRentalPremises())
                                ? request.getLandlordEiLicenceNo()
                                : null)
                .renewalCompanyNameChanged(request.getRenewalCompanyNameChanged())
                .renewalAddressChanged(request.getRenewalAddressChanged())
                .installationAddressBlock(request.getInstallationAddressBlock())
                .installationAddressUnit(request.getInstallationAddressUnit())
                .installationAddressStreet(request.getInstallationAddressStreet())
                .installationAddressBuilding(request.getInstallationAddressBuilding())
                .installationAddressPostalCode(request.getInstallationAddressPostalCode())
                .correspondenceAddressBlock(request.getCorrespondenceAddressBlock())
                .correspondenceAddressUnit(request.getCorrespondenceAddressUnit())
                .correspondenceAddressStreet(request.getCorrespondenceAddressStreet())
                .correspondenceAddressBuilding(request.getCorrespondenceAddressBuilding())
                .correspondenceAddressPostalCode(request.getCorrespondenceAddressPostalCode())
                .build();

        // 승인된 LEW 자동 할당 (applicant 경로와 동일 — kVA 처리 가능한 LEW 만 카운트)
        Integer kvaForOnBehalf = request.getSelectedKva();
        List<User> eligibleLewsOnBehalf = userRepository.findByRoleAndApprovedStatus(
                UserRole.LEW, ApprovalStatus.APPROVED).stream()
                .filter(lew -> kvaForOnBehalf != null && lew.canHandleKva(kvaForOnBehalf))
                .toList();
        if (eligibleLewsOnBehalf.size() == 1) {
            application.assignLew(eligibleLewsOnBehalf.get(0));
            log.info("LEW auto-assigned on-behalf: lewSeq={}", eligibleLewsOnBehalf.get(0).getUserSeq());
        }

        Application saved = applicationRepository.save(application);
        log.info("Application created ON-BEHALF: seq={}, targetApplicantSeq={}, conciergeRequestSeq={}, type={}, kva={}, amount={}",
                saved.getApplicationSeq(), targetApplicantSeq, conciergeRequestSeq, appType,
                request.getSelectedKva(), quoteAmount);

        // SLD 요청 자동 생성 (applicant 경로와 동일)
        if (sldOption == SldOption.REQUEST_LEW) {
            SldRequest sldRequest = SldRequest.builder()
                    .application(saved)
                    .applicantNote(null)
                    .build();
            sldRequestRepository.save(sldRequest);
            log.info("SLD request auto-created on-behalf: applicationSeq={}", saved.getApplicationSeq());
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

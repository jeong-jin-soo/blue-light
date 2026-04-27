package com.bluelight.backend.service.cof;

import com.bluelight.backend.api.application.dto.ApplicationResponse;
import com.bluelight.backend.api.lew.dto.CertificateOfFitnessRequest;
import com.bluelight.backend.api.lew.dto.CertificateOfFitnessResponse;
import com.bluelight.backend.api.lew.dto.LewApplicationResponse;
import com.bluelight.backend.api.notification.NotificationService;
import com.bluelight.backend.common.crypto.FieldEncryptionUtil;
import com.bluelight.backend.common.crypto.HmacUtil;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.common.exception.CofErrorCode;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.domain.application.KvaStatus;
import com.bluelight.backend.domain.application.SldOption;
import com.bluelight.backend.domain.application.SldRequest;
import com.bluelight.backend.domain.application.SldRequestRepository;
import com.bluelight.backend.domain.application.SldRequestStatus;
import com.bluelight.backend.domain.cof.CertificateOfFitness;
import com.bluelight.backend.domain.cof.CertificateOfFitnessRepository;
import com.bluelight.backend.domain.cof.ConsumerType;
import com.bluelight.backend.domain.cof.RetailerCode;
import com.bluelight.backend.domain.document.DocumentRequestRepository;
import com.bluelight.backend.domain.document.DocumentRequestStatus;
import com.bluelight.backend.domain.notification.NotificationType;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * LEW Review Form — CoF 조회/저장/확정 서비스 (lew-review-form-spec.md §3).
 *
 * <p>접근 제어는 컨트롤러의 {@code @PreAuthorize("@appSec.isAssignedLew(#id, authentication)")}에서
 * 일차 방어하고, 서비스 진입 시 {@link #assertAssignedLew(Application, Long)}로 이중 방어한다
 * (layer-defense — AC §9-3).</p>
 *
 * <h3>흐름</h3>
 * <ul>
 *   <li>{@link #getAssignedApplication} — 조회, 감사는 컨트롤러 어노테이션으로</li>
 *   <li>{@link #saveDraftCof} — CoF Upsert, finalized 이후 호출 시 409</li>
 *   <li>{@link #finalizeCof} — 필수 필드 전수 재검증 + 상태 전이 PENDING_REVIEW → PENDING_PAYMENT</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LewReviewService {

    /** MSSL Account No 정규식 (스펙 §3.2). */
    private static final Pattern MSSL_PATTERN = Pattern.compile("^\\d{3}-\\d{2}-\\d{4}-\\d$");

    /** 허용 전압 집합 (스펙 §9-8). */
    private static final Set<Integer> ALLOWED_VOLTAGES = Set.of(230, 400, 6600, 22000);

    /** 허용 점검 주기 집합 (스펙 §2.1 CHECK). */
    private static final Set<Integer> ALLOWED_INTERVALS = Set.of(6, 12, 24, 36, 60);

    /** Phase 6: finalize 가드 3종 중 DocumentRequest 미해결 판정에 사용 */
    private static final Set<DocumentRequestStatus> DOCUMENT_PENDING_STATUSES =
            Set.of(DocumentRequestStatus.REQUESTED, DocumentRequestStatus.UPLOADED);

    private final ApplicationRepository applicationRepository;
    private final CertificateOfFitnessRepository cofRepository;
    private final UserRepository userRepository;
    private final FieldEncryptionUtil fieldEncryptionUtil;
    private final HmacUtil hmacUtil;
    // Phase 6: 통합 LEW 리뷰 — finalize 가드 및 알림
    private final DocumentRequestRepository documentRequestRepository;
    private final SldRequestRepository sldRequestRepository;
    private final NotificationService notificationService;

    /** 배정 신청 상세 조회 (스펙 §3.1). */
    public LewApplicationResponse getAssignedApplication(Long applicationSeq, Long lewUserSeq) {
        Application application = loadApplication(applicationSeq);
        assertAssignedLew(application, lewUserSeq);

        CertificateOfFitness cof = cofRepository.findByApplication_ApplicationSeq(applicationSeq)
                .orElse(null);

        // EncryptedStringConverter는 읽을 때 이미 복호화된 평문을 엔티티 getter로 돌려준다.
        String landlordPlain = application.getLandlordEiLicenceNo();
        String[] correspondencePlain = new String[]{
                application.getCorrespondenceAddressBlock(),
                application.getCorrespondenceAddressUnit(),
                application.getCorrespondenceAddressStreet(),
                application.getCorrespondenceAddressBuilding()
        };
        // 신청자 hint MSSL 평문 — 엔티티 getter로 이미 복호화된 값. (converter가 passthrough일 때는 암호문 그대로 반환될 수 있음)
        String msslHintPlain = application.getApplicantMsslHintEnc();
        String cofMsslPlain = cof != null ? cof.getMsslAccountNoEnc() : null;

        return LewApplicationResponse.from(application, landlordPlain, correspondencePlain,
                msslHintPlain, cof, cofMsslPlain);
    }

    /**
     * CoF Draft Save — 신규 insert 또는 기존 update. finalized 상태면 409 반환(AC §9-5).
     *
     * <p>낙관적 락 충돌은 호출부에서 트랜잭션 커밋 시점에 발생 → GlobalExceptionHandler가 409로 변환
     * (STALE_STATE). 본 서비스는 명시적으로 {@link CofErrorCode#COF_VERSION_CONFLICT}를 던지지
     * 않고, 공통 핸들러에 위임한다.</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public CertificateOfFitnessResponse saveDraftCof(Long applicationSeq, Long lewUserSeq,
                                                     CertificateOfFitnessRequest request) {
        Application application = loadApplication(applicationSeq);
        assertAssignedLew(application, lewUserSeq);

        CertificateOfFitness cof = cofRepository.findByApplication_ApplicationSeq(applicationSeq)
                .orElse(null);
        if (cof != null && cof.isFinalized()) {
            throw new BusinessException(
                    "Certificate of Fitness is already finalized",
                    HttpStatus.CONFLICT, CofErrorCode.COF_ALREADY_FINALIZED);
        }

        // Draft Save — 개별 필드 형식 검증은 경고가 아닌 400으로 거부 (DTO @Pattern·서비스 allowed-set).
        // 필수 여부는 여기서 검사하지 않는다 (Draft는 부분 입력 허용).
        if (request.getSupplyVoltageV() != null
                && !ALLOWED_VOLTAGES.contains(request.getSupplyVoltageV())) {
            throw new BusinessException(
                    "Supply voltage must be one of 230, 400, 6600, 22000",
                    HttpStatus.BAD_REQUEST, CofErrorCode.COF_VALIDATION_FAILED);
        }
        if (request.getInspectionIntervalMonths() != null
                && !ALLOWED_INTERVALS.contains(request.getInspectionIntervalMonths())) {
            throw new BusinessException(
                    "Inspection interval must be one of 6/12/24/36/60 months",
                    HttpStatus.BAD_REQUEST, CofErrorCode.COF_VALIDATION_FAILED);
        }

        // Phase 6: CoF.approvedLoadKva는 Application.selectedKva에서 유도하는 snapshot 필드.
        // Draft 중에는 항상 Application의 현재 값을 reflect 하며, finalize 시점에 최종 스냅샷한다.
        // request.approvedLoadKva는 ignore한다 (SSOT 원칙: Application.selectedKva).
        Integer derivedApprovedLoadKva = application.getSelectedKva();

        boolean creating = (cof == null);
        if (creating) {
            cof = CertificateOfFitness.builder()
                    .application(application)
                    .consumerType(request.getConsumerType())
                    .retailerCode(request.getRetailerCode())
                    .supplyVoltageV(request.getSupplyVoltageV())
                    .approvedLoadKva(derivedApprovedLoadKva)
                    .hasGenerator(request.getHasGenerator())
                    .generatorCapacityKva(request.getGeneratorCapacityKva())
                    .inspectionIntervalMonths(request.getInspectionIntervalMonths())
                    .lewAppointmentDate(request.getLewAppointmentDate() != null
                            ? request.getLewAppointmentDate() : LocalDate.now())
                    .lewConsentDate(request.getLewConsentDate())
                    .build();
        } else {
            cof.updateFields(
                    request.getConsumerType(),
                    request.getRetailerCode(),
                    request.getSupplyVoltageV(),
                    derivedApprovedLoadKva,
                    request.getHasGenerator(),
                    request.getGeneratorCapacityKva(),
                    request.getInspectionIntervalMonths(),
                    request.getLewAppointmentDate(),
                    request.getLewConsentDate());
        }

        // MSSL 세팅 (평문 → enc/hmac/last4로 분리). Draft 중 공란 허용 — null이면 기존 값 유지가 아니라
        // "값 없음"으로 세팅 (AC §9-9 — 공란 Draft Save는 성공, Finalize만 실패).
        applyMssl(cof, request.getMsslAccountNo());

        cof.saveDraft();
        CertificateOfFitness saved = cofRepository.save(cof);
        log.info("CoF draft saved: cofSeq={}, applicationSeq={}, lewUserSeq={}, creating={}",
                saved.getCofSeq(), applicationSeq, lewUserSeq, creating);

        // 응답에서 MSSL 평문 노출 — 저장 직후라 request.getMsslAccountNo() 가 truthful plain.
        return CertificateOfFitnessResponse.from(saved, request.getMsslAccountNo());
    }

    /**
     * CoF 확정 (스펙 §3.3, AC §9-5/6/7/9/10 + Phase 6 통합 LEW 리뷰 가드).
     *
     * <h3>Phase 6 가드 (finalize 직전 3종)</h3>
     * <ol>
     *   <li>Application.kvaStatus != CONFIRMED → 400 {@code KVA_NOT_CONFIRMED}</li>
     *   <li>미해결 DocumentRequest (REQUESTED/UPLOADED) 존재 → 400 {@code DOCUMENT_REQUESTS_PENDING}</li>
     *   <li>sldOption=REQUEST_LEW 이고 SldRequest 상태가 CONFIRMED 아님 → 400 {@code SLD_NOT_CONFIRMED}</li>
     * </ol>
     *
     * <h3>스냅샷</h3>
     * finalize 직전 CoF.approvedLoadKva := Application.selectedKva 로 덮어쓴다 (법적 기록 스냅샷).
     *
     * <ul>
     *   <li>이미 finalized면 409</li>
     *   <li>MSSL 공란 / hasGenerator=true 인데 capacity null / Contestable 인데 retailer null / 필수필드 누락 → 400</li>
     *   <li>Application.status는 PENDING_REVIEW 여야 하며, 성공 시 PENDING_PAYMENT로 전이</li>
     *   <li>lewConsentDate null이면 today로 자동</li>
     *   <li>성공 시 신청자에게 {@code CERTIFICATE_OF_FITNESS_FINALIZED} 알림 발송</li>
     * </ul>
     */
    @Transactional(rollbackFor = Exception.class)
    public ApplicationResponse finalizeCof(Long applicationSeq, Long lewUserSeq) {
        Application application = loadApplication(applicationSeq);
        assertAssignedLew(application, lewUserSeq);

        CertificateOfFitness cof = cofRepository.findByApplication_ApplicationSeq(applicationSeq)
                .orElseThrow(() -> new BusinessException(
                        "Certificate of Fitness not found — save a draft first",
                        HttpStatus.NOT_FOUND, CofErrorCode.COF_NOT_FOUND));

        if (cof.isFinalized()) {
            throw new BusinessException(
                    "Certificate of Fitness is already finalized",
                    HttpStatus.CONFLICT, CofErrorCode.COF_ALREADY_FINALIZED);
        }

        // Application 상태 전제 (스펙 §3.3)
        ApplicationStatus cur = application.getStatus();
        if (cur != ApplicationStatus.PENDING_REVIEW) {
            throw new BusinessException(
                    "Application must be in PENDING_REVIEW to finalize CoF (current: " + cur + ")",
                    HttpStatus.CONFLICT, CofErrorCode.COF_VALIDATION_FAILED);
        }

        // Phase 6 가드 3종 (통합 LEW 리뷰)
        assertKvaConfirmed(application);
        assertNoPendingDocumentRequests(applicationSeq);
        assertSldConfirmedIfRequired(application, applicationSeq);

        // Phase 6: finalize 직전 Application.selectedKva를 CoF.approvedLoadKva에 스냅샷
        cof.snapshotApprovedLoadKva(application.getSelectedKva());

        // 필수 필드 전수 재검증
        validateForFinalize(cof);

        // LEW 엔티티 로딩
        User lewUser = userRepository.findById(lewUserSeq)
                .orElseThrow(() -> new BusinessException(
                        "LEW user not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        // finalize 기록 (certifiedBy/at + lewConsentDate 보정)
        cof.finalize(lewUser, cof.getLewConsentDate());
        cofRepository.save(cof);

        // 상태 전이 (AC §9-10)
        application.approveForPayment();
        log.info("CoF finalized: cofSeq={}, applicationSeq={}, lewUserSeq={}",
                cof.getCofSeq(), applicationSeq, lewUserSeq);

        // Phase 6: 신청자 알림 — 알림 실패가 확정 트랜잭션을 롤백시키지 않도록 방어
        notifyApplicantCofFinalized(application);

        return ApplicationResponse.from(application);
    }

    // ── 내부 유틸 ──────────────────────

    private Application loadApplication(Long applicationSeq) {
        return applicationRepository.findById(applicationSeq)
                .orElseThrow(() -> new BusinessException(
                        "Application not found",
                        HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND"));
    }

    private void assertAssignedLew(Application application, Long lewUserSeq) {
        User assigned = application.getAssignedLew();
        if (assigned == null || !assigned.getUserSeq().equals(lewUserSeq)) {
            throw new BusinessException(
                    "You are not assigned to this application",
                    HttpStatus.FORBIDDEN, CofErrorCode.APPLICATION_NOT_ASSIGNED);
        }
    }

    /**
     * MSSL 평문을 CoF 3종 컬럼에 분리 저장.
     * 입력이 null/blank이면 모두 null로 세팅 (Draft 중 공란 허용).
     * 입력이 있으면 regex 검증 통과해야 함 (DTO @Pattern이 일차 방어).
     */
    private void applyMssl(CertificateOfFitness cof, String plain) {
        if (plain == null || plain.isBlank()) {
            cof.updateMssl(null, null, null);
            return;
        }
        if (!MSSL_PATTERN.matcher(plain).matches()) {
            throw new BusinessException(
                    "MSSL Account No must match format ###-##-####-#",
                    HttpStatus.BAD_REQUEST, CofErrorCode.COF_VALIDATION_FAILED);
        }
        String enc = fieldEncryptionUtil.encrypt(plain);
        String hmac = hmacUtil.hmac(plain);
        String last4 = extractLast4(plain);
        cof.updateMssl(enc, hmac, last4);
    }

    private static String extractLast4(String mssl) {
        StringBuilder digits = new StringBuilder();
        for (int i = mssl.length() - 1; i >= 0 && digits.length() < 4; i--) {
            char c = mssl.charAt(i);
            if (Character.isDigit(c)) digits.insert(0, c);
        }
        return digits.length() == 4 ? digits.toString() : null;
    }

    /**
     * finalize 직전 CoF의 필수 필드 전수 검증 (AC §9-6, §9-7, §9-9).
     */
    private void validateForFinalize(CertificateOfFitness cof) {
        // MSSL 공란 → 실패 (AC §9-9)
        if (cof.getMsslAccountNoHmac() == null || cof.getMsslAccountNoLast4() == null) {
            throw new BusinessException(
                    "MSSL Account No is required to finalize",
                    HttpStatus.BAD_REQUEST, CofErrorCode.COF_VALIDATION_FAILED);
        }
        // 숫자/enum 필수 필드
        if (cof.getConsumerType() == null) {
            throw new BusinessException("Consumer type is required",
                    HttpStatus.BAD_REQUEST, CofErrorCode.COF_VALIDATION_FAILED);
        }
        if (cof.getSupplyVoltageV() == null) {
            throw new BusinessException("Supply voltage is required",
                    HttpStatus.BAD_REQUEST, CofErrorCode.COF_VALIDATION_FAILED);
        }
        if (cof.getApprovedLoadKva() == null) {
            throw new BusinessException("Approved load (kVA) is required",
                    HttpStatus.BAD_REQUEST, CofErrorCode.COF_VALIDATION_FAILED);
        }
        if (cof.getInspectionIntervalMonths() == null) {
            throw new BusinessException("Inspection interval is required",
                    HttpStatus.BAD_REQUEST, CofErrorCode.COF_VALIDATION_FAILED);
        }
        if (cof.getLewAppointmentDate() == null) {
            throw new BusinessException("LEW appointment date is required",
                    HttpStatus.BAD_REQUEST, CofErrorCode.COF_VALIDATION_FAILED);
        }
        // Contestable → retailer 필수 (AC §9-7)
        if (cof.getConsumerType() == ConsumerType.CONTESTABLE && cof.getRetailerCode() == null) {
            throw new BusinessException(
                    "Retailer code is required for contestable consumers",
                    HttpStatus.BAD_REQUEST, CofErrorCode.COF_VALIDATION_FAILED);
        }
        // hasGenerator=true → capacity 필수 (AC §9-6)
        if (Boolean.TRUE.equals(cof.getHasGenerator()) && cof.getGeneratorCapacityKva() == null) {
            throw new BusinessException(
                    "Generator capacity (kVA) is required when a generator is present",
                    HttpStatus.BAD_REQUEST, CofErrorCode.COF_VALIDATION_FAILED);
        }
    }

    /**
     * 낙관적 락 예외를 명시적 에러 코드로 변환해 던진다 (AC §9-12).
     * 공통 GlobalExceptionHandler가 STALE_STATE로 변환하지만, 본 경로는 좀 더 구체적인
     * {@code COF_VERSION_CONFLICT} 코드를 원한다면 컨트롤러에서 본 메서드를 try/catch로 감싸 사용.
     */
    @SuppressWarnings("unused")
    private BusinessException toVersionConflict(ObjectOptimisticLockingFailureException e) {
        return new BusinessException(
                "CoF was updated concurrently — refresh and retry",
                HttpStatus.CONFLICT, CofErrorCode.COF_VERSION_CONFLICT);
    }

    // ── Phase 6: 통합 LEW 리뷰 가드 및 알림 ──────────────────────

    /** Phase 6 가드 1: Application.kvaStatus 가 CONFIRMED 이어야 finalize 가능. */
    private void assertKvaConfirmed(Application application) {
        if (application.getKvaStatus() != KvaStatus.CONFIRMED) {
            throw new BusinessException(
                    "kVA must be confirmed before finalizing CoF (current kvaStatus: "
                            + application.getKvaStatus() + ")",
                    HttpStatus.BAD_REQUEST, CofErrorCode.KVA_NOT_CONFIRMED);
        }
    }

    /** Phase 6 가드 2: 미해결 DocumentRequest(REQUESTED/UPLOADED) 가 없어야 finalize 가능. */
    private void assertNoPendingDocumentRequests(Long applicationSeq) {
        long pending = documentRequestRepository.countByApplicationAndStatusIn(
                applicationSeq, DOCUMENT_PENDING_STATUSES);
        if (pending > 0) {
            throw new BusinessException(
                    "There are " + pending + " pending document request(s) — resolve them before finalizing",
                    HttpStatus.BAD_REQUEST, CofErrorCode.DOCUMENT_REQUESTS_PENDING);
        }
    }

    /** Phase 6 가드 3: sldOption=REQUEST_LEW 이면 SldRequest.status 가 CONFIRMED 이어야 finalize 가능. */
    private void assertSldConfirmedIfRequired(Application application, Long applicationSeq) {
        if (application.getSldOption() != SldOption.REQUEST_LEW) {
            return;
        }
        SldRequest sldRequest = sldRequestRepository.findByApplicationApplicationSeq(applicationSeq)
                .orElse(null);
        if (sldRequest == null || sldRequest.getStatus() != SldRequestStatus.CONFIRMED) {
            throw new BusinessException(
                    "SLD must be uploaded and confirmed before finalizing CoF (current: "
                            + (sldRequest == null ? "NONE" : sldRequest.getStatus()) + ")",
                    HttpStatus.BAD_REQUEST, CofErrorCode.SLD_NOT_CONFIRMED);
        }
    }

    /**
     * 신청자에게 CoF finalize 알림 발송. 알림 실패는 swallow 하여 확정 트랜잭션을 롤백하지 않는다
     * ({@link #notifyApplicant}와 동일 원칙).
     */
    private void notifyApplicantCofFinalized(Application application) {
        try {
            Long recipientSeq = application.getUser() != null
                    ? application.getUser().getUserSeq() : null;
            if (recipientSeq == null) {
                return;
            }
            notificationService.createNotification(
                    recipientSeq, NotificationType.CERTIFICATE_OF_FITNESS_FINALIZED,
                    "Certificate of Fitness signed",
                    "Your LEW has signed the Certificate of Fitness. Your application is now awaiting payment.",
                    "Application", application.getApplicationSeq());
        } catch (RuntimeException ex) {
            log.warn("CoF finalize 알림 발송 실패: applicationId={}, err={}",
                    application.getApplicationSeq(), ex.getMessage());
        }
    }
}

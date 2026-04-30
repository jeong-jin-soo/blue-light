package com.bluelight.backend.service.cof;

import com.bluelight.backend.api.application.dto.ApplicationResponse;
import com.bluelight.backend.api.email.EmailService;
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
 * LEW Review Form — CoF 조회/저장/확정 + 결제 요청 서비스 (lew-review-form-spec.md §3, PR3 옵션 R).
 *
 * <p>접근 제어는 컨트롤러의 {@code @PreAuthorize("@appSec.isAssignedLew(#id, authentication)")}에서
 * 일차 방어하고, 서비스 진입 시 {@link #assertAssignedLew(Application, Long)}로 이중 방어한다
 * (layer-defense — AC §9-3).</p>
 *
 * <h3>흐름</h3>
 * <ul>
 *   <li>{@link #getAssignedApplication} — 조회, 감사는 컨트롤러 어노테이션으로</li>
 *   <li>{@link #saveDraftCof} — CoF Upsert, finalized 이후 호출 시 409</li>
 *   <li>{@link #requestPayment} — PR3: Phase 1(검토·서류·kVA) 종료 후 LEW가 결제 단계로 전이를 트리거.
 *       PENDING_REVIEW/REVISION_REQUESTED → PENDING_PAYMENT</li>
 *   <li>{@link #finalizeCof} — PR3 옵션 R: 결제 완료(PAID/IN_PROGRESS) 후에만 호출 가능. status 전이 없음.
 *       SS 638 §13(시공·테스트 후 CoF 발행) 준수.</li>
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
    // PR3: LEW가 결제 요청 트리거 시 신청자 메일 발송 (ADMIN 흐름과 동일)
    private final EmailService emailService;

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
     * CoF 확정 (스펙 §3.3 + PR3 옵션 R: 결제 후 발행).
     *
     * <h3>PR3 도메인 모델 변경</h3>
     * <p>SS 638 §13 (sg-lew-expert 검증) — CoF는 시공·테스트 후 발행되어야 하므로,
     * 결제 완료 이후(PAID/IN_PROGRESS)에만 finalize 호출 가능. 이전 모델은 finalize가
     * PENDING_REVIEW → PENDING_PAYMENT 전이를 일으켜 도메인 부정합이었다.
     * PR3에서 결제 트리거는 별도 endpoint({@link #requestPayment})로 분리.</p>
     *
     * <h3>가드</h3>
     * <ol>
     *   <li>이미 finalized → 409 {@code COF_ALREADY_FINALIZED}</li>
     *   <li>{@link Application#getStatus()} ∉ {PAID, IN_PROGRESS} → 409 {@code APPLICATION_NOT_PAID}
     *       (PR3 신규 가드 — 결제 게이트)</li>
     *   <li>kvaStatus != CONFIRMED → 400 {@code KVA_NOT_CONFIRMED}</li>
     *   <li>미해결 DocumentRequest 존재 → 400 {@code DOCUMENT_REQUESTS_PENDING}</li>
     *   <li>sldOption=REQUEST_LEW 이고 SldRequest CONFIRMED 아님 → 400 {@code SLD_NOT_CONFIRMED}</li>
     *   <li>필수 필드 누락(MSSL 공란, hasGenerator=true에 capacity null, Contestable에 retailer null 등)
     *       → 400 {@code COF_VALIDATION_FAILED}</li>
     * </ol>
     *
     * <h3>스냅샷 / 사이드이펙트</h3>
     * <ul>
     *   <li>finalize 직전 CoF.approvedLoadKva := Application.selectedKva 로 덮어쓴다 (법적 기록 스냅샷)</li>
     *   <li>certifiedBy/at + lewConsentDate(null이면 today) 기록</li>
     *   <li><b>Application.status 전이는 발생하지 않는다</b> (이미 PAID/IN_PROGRESS 상태)</li>
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

        // PR3 결제 게이트: PAID/IN_PROGRESS 가 아닌 상태에서 finalize 호출 시 거부.
        // SS 638 §13에 따라 시공·테스트 후 CoF 발행 — 결제 완료가 시공 진입의 전제.
        ApplicationStatus cur = application.getStatus();
        if (cur != ApplicationStatus.PAID && cur != ApplicationStatus.IN_PROGRESS) {
            throw new BusinessException(
                    "Payment must be confirmed before finalizing CoF (current status: " + cur + ")",
                    HttpStatus.CONFLICT, CofErrorCode.APPLICATION_NOT_PAID);
        }

        // Phase 6 가드 3종 — Phase 1 완료 + SLD 충족이 finalize 전제 (결제 게이트와 별개로 유지)
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

        // finalize 기록 (certifiedBy/at + lewConsentDate 보정).
        // PR3: status 전이는 없다 — 이미 PAID/IN_PROGRESS 상태이므로 LEW가 시공 후 CoF만 서명한다.
        cof.finalize(lewUser, cof.getLewConsentDate());
        cofRepository.save(cof);

        log.info("CoF finalized (status unchanged): cofSeq={}, applicationSeq={}, status={}, lewUserSeq={}",
                cof.getCofSeq(), applicationSeq, cur, lewUserSeq);

        // 신청자 알림 — 알림 실패가 확정 트랜잭션을 롤백시키지 않도록 방어
        notifyApplicantCofFinalized(application);

        return ApplicationResponse.from(application);
    }

    /**
     * PR3: LEW가 명시적으로 결제 요청을 트리거 (옵션 R — sg-lew-expert + 사용자 결정).
     *
     * <p>Phase 1 (검토 + 서류 보강 + kVA 확정) 종료 후, LEW가 직접 호출하여 status 를
     * {@code PENDING_REVIEW/REVISION_REQUESTED → PENDING_PAYMENT} 로 전이시킨다. CoF/SLD/LOA 작업은
     * 결제 후 단계로 이동.</p>
     *
     * <h3>가드 (서버측 재검증 필수)</h3>
     * <ol>
     *   <li>현재 status ∈ {PENDING_REVIEW, REVISION_REQUESTED} 가 아니면 → 409 {@code INVALID_STATUS_TRANSITION}.
     *       ADMIN의 별도 approveForPayment 와 race 발생 시 두 번째 호출이 이 코드로 거부된다.</li>
     *   <li>{@code Application.kvaStatus != CONFIRMED} → 409 {@code KVA_NOT_CONFIRMED}</li>
     *   <li>미해결 DocumentRequest(REQUESTED/UPLOADED) 존재 → 409 {@code DOCUMENT_REQUESTS_PENDING}</li>
     * </ol>
     *
     * <p>SLD 가드는 PR3 범위에서 적용하지 않는다 — sldOption=REQUEST_LEW 이면 SLD 작업은 결제 후
     * 수행되며, 결제 요청 시점에는 SLD가 아직 미준비 상태일 수 있다.</p>
     *
     * <h3>사이드이펙트</h3>
     * <ul>
     *   <li>{@link Application#approveForPayment()} 호출 → status PENDING_PAYMENT</li>
     *   <li>신청자에게 결제 요청 이메일 발송 (ADMIN 흐름과 동일한 메일 사용)</li>
     * </ul>
     */
    @Transactional(rollbackFor = Exception.class)
    public ApplicationResponse requestPayment(Long applicationSeq, Long lewUserSeq) {
        Application application = loadApplication(applicationSeq);
        assertAssignedLew(application, lewUserSeq);

        // 1) status 가드: PENDING_REVIEW 또는 REVISION_REQUESTED 만 허용
        ApplicationStatus cur = application.getStatus();
        if (cur != ApplicationStatus.PENDING_REVIEW && cur != ApplicationStatus.REVISION_REQUESTED) {
            throw new BusinessException(
                    "Payment can only be requested from PENDING_REVIEW or REVISION_REQUESTED (current: "
                            + cur + ")",
                    HttpStatus.CONFLICT, CofErrorCode.INVALID_STATUS_TRANSITION);
        }

        // 2) Phase 1 종료 가드 (LEW가 검토를 끝냈는지 재확인) — 상태 충돌이므로 409
        assertKvaConfirmed(application, HttpStatus.CONFLICT);
        assertNoPendingDocumentRequests(applicationSeq, HttpStatus.CONFLICT);

        // 상태 전이 — 도메인 메서드 사용 (reviewComment 클리어 포함)
        application.approveForPayment();
        log.info("LEW requested payment: applicationSeq={}, lewUserSeq={}, prevStatus={}",
                applicationSeq, lewUserSeq, cur);

        // 신청자에게 결제 요청 이메일 발송 — ADMIN 흐름과 동일. 실패가 트랜잭션을 깨뜨리지 않도록 방어.
        notifyPaymentRequested(application);

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

    /**
     * Phase 6 가드 1: {@link Application#getKvaStatus()} 가 CONFIRMED 이어야 finalize/requestPayment 가능.
     *
     * <p>HTTP 상태는 호출자가 결정한다 — finalize 경로는 입력 부정합으로 400, requestPayment 경로는
     * 상태 충돌로 409 (PR3 spec).</p>
     */
    private void assertKvaConfirmed(Application application) {
        assertKvaConfirmed(application, HttpStatus.BAD_REQUEST);
    }

    private void assertKvaConfirmed(Application application, HttpStatus status) {
        if (application.getKvaStatus() != KvaStatus.CONFIRMED) {
            throw new BusinessException(
                    "kVA must be confirmed first (current kvaStatus: "
                            + application.getKvaStatus() + ")",
                    status, CofErrorCode.KVA_NOT_CONFIRMED);
        }
    }

    /** Phase 6 가드 2: 미해결 DocumentRequest(REQUESTED/UPLOADED) 가 없어야 finalize/requestPayment 가능. */
    private void assertNoPendingDocumentRequests(Long applicationSeq) {
        assertNoPendingDocumentRequests(applicationSeq, HttpStatus.BAD_REQUEST);
    }

    private void assertNoPendingDocumentRequests(Long applicationSeq, HttpStatus status) {
        long pending = documentRequestRepository.countByApplicationAndStatusIn(
                applicationSeq, DOCUMENT_PENDING_STATUSES);
        if (pending > 0) {
            throw new BusinessException(
                    "There are " + pending + " pending document request(s) — resolve them first",
                    status, CofErrorCode.DOCUMENT_REQUESTS_PENDING);
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
     * 신청자에게 CoF finalize 알림 발송. 알림 실패는 swallow 하여 확정 트랜잭션을 롤백하지 않는다.
     *
     * <p>PR3 옵션 R: finalize는 결제 후(PAID/IN_PROGRESS)에 일어나므로 메시지에서 "awaiting payment"
     * 문구를 제거하고 "license is being processed" 로 변경한다.</p>
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
                    "Your LEW has signed the Certificate of Fitness. Your licence is being processed.",
                    "Application", application.getApplicationSeq());
        } catch (RuntimeException ex) {
            log.warn("CoF finalize 알림 발송 실패: applicationId={}, err={}",
                    application.getApplicationSeq(), ex.getMessage());
        }
    }

    /**
     * PR3: LEW가 결제 요청을 트리거하면 신청자에게 결제 요청 이메일 발송.
     * ADMIN 흐름({@code AdminApplicationService.approveForPayment})과 동일한 메일 템플릿 사용.
     * 메일 발송 실패는 swallow 하여 상태 전이 트랜잭션을 롤백하지 않는다.
     */
    private void notifyPaymentRequested(Application application) {
        try {
            User applicant = application.getUser();
            if (applicant == null || applicant.getEmail() == null) {
                log.warn("결제 요청 메일 발송 스킵 — 신청자 정보 없음: applicationId={}",
                        application.getApplicationSeq());
                return;
            }
            emailService.sendPaymentRequestEmail(
                    applicant.getEmail(),
                    (applicant.getFirstName() != null ? applicant.getFirstName() : "") + " "
                            + (applicant.getLastName() != null ? applicant.getLastName() : ""),
                    application.getApplicationSeq(),
                    application.getAddress(),
                    application.getQuoteAmount());
        } catch (RuntimeException ex) {
            log.warn("결제 요청 메일 발송 실패 (LEW trigger): applicationId={}, err={}",
                    application.getApplicationSeq(), ex.getMessage());
        }
    }
}

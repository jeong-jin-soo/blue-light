package com.bluelight.backend.domain.application;

import com.bluelight.backend.common.crypto.EncryptedStringConverter;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.common.BaseEntity;
import com.bluelight.backend.domain.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 라이선스 신청 내역 Entity.
 *
 * <h2>LOA 스냅샷 컬럼 불변 정책 (Phase 2 PR#4 / Security B-5)</h2>
 * {@code loaApplicantNameSnapshot}, {@code loaCompanyNameSnapshot},
 * {@code loaUenSnapshot}, {@code loaDesignationSnapshot} 4개 컬럼은
 * LOA 생성 시점의 신청자 신원 정보를 보존하는 법적 문서 무결성 요건이다.
 * <ul>
 *   <li>JPA 레벨에서 {@code @Column(updatable = false)}로 UPDATE 강제 차단.</li>
 *   <li>교정이 필요한 경우, 기존 LOA를 revoke하고 신규 LOA를 재발급한다
 *       (엔티티 자체를 분리하지 않는 현재 구조에서는 LOA 파일 재생성 + FileEntity 갱신).</li>
 *   <li>관리자 권한으로도 UPDATE 금지 — 운영 절차로 관리.</li>
 *   <li>{@code loaSnapshotBackfilledAt}는 V_04 마이그레이션으로 백필된 row 식별용.
 *       법적 쟁송 시 원본 생성 시점 vs 백필 시점을 구분할 수 있다.</li>
 * </ul>
 */
@Entity
@Table(name = "applications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLDelete(sql = "UPDATE applications SET deleted_at = NOW() WHERE application_seq = ?")
@SQLRestriction("deleted_at IS NULL")
public class Application extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "application_seq")
    private Long applicationSeq;

    /**
     * 신청자 (FK)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_seq", nullable = false)
    private User user;

    /**
     * 현장 주소
     */
    @Column(name = "address", nullable = false, length = 255)
    private String address;

    /**
     * 우편번호
     */
    @Column(name = "postal_code", nullable = false, length = 10)
    private String postalCode;

    /**
     * 건물 유형
     */
    @Column(name = "building_type", length = 50)
    private String buildingType;

    /**
     * 선택한 DB Size (kVA)
     */
    @Column(name = "selected_kva", nullable = false)
    private Integer selectedKva;

    /**
     * 결제 대상 금액 (SGD)
     */
    @Column(name = "quote_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal quoteAmount;

    /**
     * 진행 상태
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ApplicationStatus status = ApplicationStatus.PENDING_REVIEW;

    /**
     * 라이선스 번호 (발급 후 설정)
     */
    @Column(name = "license_number", length = 50)
    private String licenseNumber;

    /**
     * 라이선스 만료일 (발급 후 설정)
     */
    @Column(name = "license_expiry_date")
    private LocalDate licenseExpiryDate;

    /**
     * LEW 리뷰 코멘트 (보완 요청 사유)
     */
    @Column(name = "review_comment", columnDefinition = "TEXT")
    private String reviewComment;

    /**
     * 담당 LEW (할당된 경우, nullable)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_lew_seq")
    private User assignedLew;

    /**
     * SP Group 계정 번호
     */
    @Column(name = "sp_account_no", length = 30)
    private String spAccountNo;

    // ── Phase 18: 갱신 + 견적 개선 필드 ──

    /**
     * 신청 유형 (NEW / RENEWAL)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "application_type", nullable = false)
    private ApplicationType applicationType = ApplicationType.NEW;

    /**
     * 신청자 유형 (INDIVIDUAL / CORPORATE) — Phase 1 추가
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "applicant_type", nullable = false)
    private ApplicantType applicantType = ApplicantType.INDIVIDUAL;

    /**
     * SLD 작성 비용 (REQUEST_LEW 시에만 설정, 생성 시점 스냅샷)
     */
    @Column(name = "sld_fee", precision = 10, scale = 2)
    private BigDecimal sldFee;

    /**
     * 출장비 (call-out fee) — New License 신청에만 설정 (Renewal 은 null), 생성 시점 스냅샷
     */
    @Column(name = "callout_fee", precision = 10, scale = 2)
    private BigDecimal calloutFee;

    /**
     * 원본 신청 (갱신 시 참조, nullable)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "original_application_seq")
    private Application originalApplication;

    /**
     * 기존 면허 번호 (갱신 시)
     */
    @Column(name = "existing_licence_no", length = 50)
    private String existingLicenceNo;

    /**
     * 갱신 참조 번호
     */
    @Column(name = "renewal_reference_no", length = 50)
    private String renewalReferenceNo;

    /**
     * 기존 면허 만료일 (갱신 시)
     */
    @Column(name = "existing_expiry_date")
    private LocalDate existingExpiryDate;

    /**
     * 갱신 기간 (3 or 12 개월)
     */
    @Column(name = "renewal_period_months")
    private Integer renewalPeriodMonths;

    /**
     * EMA 수수료 (안내용, 3개월=$50, 12개월=$100)
     */
    @Column(name = "ema_fee", precision = 10, scale = 2)
    private BigDecimal emaFee;

    /**
     * SLD 제출 방식 (SELF_UPLOAD / REQUEST_LEW)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "sld_option")
    private SldOption sldOption = SldOption.SELF_UPLOAD;

    /**
     * LOA 서명 이미지 경로 (전자서명 PNG)
     */
    @Column(name = "loa_signature_url", length = 255)
    private String loaSignatureUrl;

    /**
     * LOA 서명 일시
     */
    @Column(name = "loa_signed_at")
    private LocalDateTime loaSignedAt;

    // ── LOA 서명 출처 (★ Kaki Concierge v1.5, Phase 1 PR#1 Stage 3) ──
    // PRD §3.4a / §7.2.1-LOA 3-경로 모델.
    // 주의: updatable=false는 INSERT 시점에만 세팅 가능한 컬럼에 적용.
    // 서명은 Application 생성 후 별도 시점에 발생하므로 updatable=false 적용 시 UPDATE 차단됨
    // (PR#3에서 User.activatedAt 동일 버그로 발견). 불변성은 도메인 메서드 가드로 보장.

    /**
     * LOA 서명 출처 (APPLICANT_DIRECT / MANAGER_UPLOAD / REMOTE_LINK).
     * 도메인 메서드 {@link #recordLoaSignatureSource}가 최초 1회 + 동일 source만 멱등 허용.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "loa_signature_source", length = 30)
    private LoaSignatureSource loaSignatureSource;

    /**
     * MANAGER_UPLOAD 경로 시 업로드한 Manager (APPLICANT_DIRECT는 null).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loa_signature_uploaded_by")
    private User loaSignatureUploadedBy;

    /**
     * LOA 서명 출처 기록 시점.
     */
    @Column(name = "loa_signature_uploaded_at")
    private LocalDateTime loaSignatureUploadedAt;

    /**
     * Manager 대리 업로드 시 수령 경로 메모 (예: "applicant emailed PDF on 2026-04-19").
     */
    @Column(name = "loa_signature_source_memo", length = 500)
    private String loaSignatureSourceMemo;

    // ── Concierge 대리 생성 연결 (★ Kaki Concierge v1.5 Phase 1 PR#5 Stage A) ──

    /**
     * Concierge Manager 대리 생성 시 연결된 ConciergeRequest seq.
     * <ul>
     *   <li>APPLICANT 직접 신청: {@code null}</li>
     *   <li>CONCIERGE_MANAGER 대리 생성: {@code ConciergeRequest.seq}</li>
     * </ul>
     * {@code updatable=false} — INSERT 시 1회만 기록, 이후 변경 불가.
     * FK 제약은 schema에 걸지 않음 (concierge_requests soft-delete와의 상호작용 회피).
     */
    @Column(name = "via_concierge_request_seq", updatable = false)
    private Long viaConciergeRequestSeq;

    // ── LOA 스냅샷 컬럼 (Phase 2 PR#4 / Security B-5) ──
    // 클래스 JavaDoc의 "LOA 스냅샷 컬럼 불변 정책" 참조.

    /**
     * LOA 생성 시점 신청자 성명 스냅샷.
     * 신규 LOA는 항상 기록 (NOT NULL), 백필 row는 비어있지 않더라도 {@link #loaSnapshotBackfilledAt}로 구분.
     */
    @Column(name = "applicant_name_snapshot", length = 100, updatable = false)
    private String loaApplicantNameSnapshot;

    /**
     * LOA 생성 시점 회사명 스냅샷. 개인 신청은 null 가능.
     */
    @Column(name = "company_name_snapshot", length = 100, updatable = false)
    private String loaCompanyNameSnapshot;

    /**
     * LOA 생성 시점 UEN 스냅샷. 개인 신청은 null 가능.
     */
    @Column(name = "uen_snapshot", length = 20, updatable = false)
    private String loaUenSnapshot;

    /**
     * LOA 생성 시점 직책 스냅샷. 개인 신청은 null 가능.
     */
    @Column(name = "designation_snapshot", length = 50, updatable = false)
    private String loaDesignationSnapshot;

    // ── LoA 교환 모델 (loa-exchange 재설계 PR3) ──
    /**
     * LoA 진행 단계 (파일 교환 모델). 기존 디지털 서명 필드(loaSignatureUrl 등)를 대체.
     * 신청 생성 시 NOT_STARTED. 전이는 도메인 메서드(markFormSent/markApplicantUploaded/markFinalUploaded).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "loa_stage", length = 30, nullable = false)
    private LoaStage loaStage = LoaStage.NOT_STARTED;

    /**
     * (NEW 전용) 신청자에게 전달된 LoA 폼 템플릿 버전 스냅샷. 전달 시점 active 버전으로 1회 고정.
     * 이후 admin이 폼을 교체해도 이 신청은 전달받은 버전 유지(법적 추적). RENEWAL은 null.
     */
    @Column(name = "loa_form_template_seq")
    private Long loaFormTemplateSeq;

    /**
     * 신청 시점 phone 스냅샷 (C.1 Snapshot-at-submit / SMS 용).
     * {@code updatable=false} — LOA 스냅샷 불변 정책 동일 적용.
     */
    @Column(name = "loa_phone_snapshot", length = 20, updatable = false)
    private String loaPhoneSnapshot;

    /**
     * 신청 시점 email 스냅샷 (C.1 Snapshot-at-submit).
     * {@code updatable=false} — LOA 스냅샷 불변 정책 동일 적용.
     */
    @Column(name = "loa_email_snapshot", length = 100, updatable = false)
    private String loaEmailSnapshot;

    /**
     * 스냅샷이 백필로 채워진 시각(Security R-2).
     * 신규 LOA 생성 시에는 null, V_04 마이그레이션 백필 대상은 NOW() 기록.
     */
    @Column(name = "snapshot_backfilled_at", updatable = false)
    private LocalDateTime loaSnapshotBackfilledAt;

    /**
     * 만료 알림 발송 시각 (중복 알림 방지)
     */
    @Column(name = "expiry_notified_at")
    private LocalDateTime expiryNotifiedAt;

    // ── Phase 5: kVA 확정 상태 ──
    // 상세: doc/Project execution/phase5-kva-ux/01-spec.md §3
    // 보안: doc/Project execution/phase5-kva-ux/03-security-review.md §1,§3

    /**
     * kVA 확정 상태 (UNKNOWN | CONFIRMED).
     * <p>기본값 {@code CONFIRMED} — 하위호환 (기존 레코드 + kvaStatus 누락 요청 모두 CONFIRMED 로 간주).
     * <p>{@code UNKNOWN} 인 경우 결제 단계 진입 차단 (B-1 가드).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "kva_status", nullable = false, length = 20)
    private KvaStatus kvaStatus = KvaStatus.CONFIRMED;

    /**
     * kVA 값 출처 (USER_INPUT | LEW_VERIFIED).
     * <p>{@code kvaStatus=UNKNOWN} 일 때는 {@code null}, {@code CONFIRMED} 일 때는 필수.
     * <p>schema CHECK 제약으로 일관성 강제.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "kva_source", length = 20)
    private KvaSource kvaSource;

    /**
     * LEW/ADMIN 이 kVA 를 확정한 경우 확정자 (FK → users).
     * <p>USER_INPUT 경로에서는 {@code null}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "kva_confirmed_by")
    private User kvaConfirmedBy;

    /**
     * LEW/ADMIN 이 kVA 를 확정한 시각.
     */
    @Column(name = "kva_confirmed_at")
    private LocalDateTime kvaConfirmedAt;

    /**
     * 낙관적 락 버전 (Security B-2).
     * <p>동시성 공격 방어 — kVA 확정과 승인 경로가 동시에 실행되어도 한 건만 성공.
     * <p>충돌 시 {@link org.springframework.orm.ObjectOptimisticLockingFailureException} →
     * {@code GlobalExceptionHandler} 에서 409 {@code STALE_STATE} 로 변환.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    // ── P1.1: EMA ELISE 필드 — 저장소 준비 (DTO/Service 전파는 P1.2에서) ──

    /** EMA ELISE "Installation Name" — 사이트 호칭. */
    @Column(name = "installation_name", length = 200)
    private String installationName;

    /** EMA ELISE "Premises Type" — 용도 분류. */
    @Enumerated(EnumType.STRING)
    @Column(name = "premises_type", length = 30)
    private PremisesType premisesType;

    /** 설치 장소가 임대 건물인지 여부. */
    @Column(name = "is_rental_premises")
    private Boolean isRentalPremises;

    /** 임대주의 EI Licence 번호 — 임대일 때만 수집 (PDPA: 개인정보, 암호화 대상). */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "landlord_ei_licence_no", length = 255)
    private String landlordEiLicenceNo;

    /** 갱신 시: 회사명이 바뀌었는지 여부 (바뀌면 추가 서류 필요). */
    @Column(name = "renewal_company_name_changed")
    private Boolean renewalCompanyNameChanged;

    /** 갱신 시: 주소가 바뀌었는지 여부. */
    @Column(name = "renewal_address_changed")
    private Boolean renewalAddressChanged;

    // Installation Address — 5-part, 평문 (ELISE가 block/unit/street/building/postal 개별 전송 요구)
    @Column(name = "installation_address_block", length = 20)
    private String installationAddressBlock;

    @Column(name = "installation_address_unit", length = 20)
    private String installationAddressUnit;

    @Column(name = "installation_address_street", length = 200)
    private String installationAddressStreet;

    @Column(name = "installation_address_building", length = 200)
    private String installationAddressBuilding;

    @Column(name = "installation_address_postal_code", length = 10)
    private String installationAddressPostalCode;

    // Correspondence Address — Block/Unit/Street/Building은 암호화, Postal은 평문 (PDPA 분석서 지침)
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "correspondence_address_block", length = 255)
    private String correspondenceAddressBlock;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "correspondence_address_unit", length = 255)
    private String correspondenceAddressUnit;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "correspondence_address_street", length = 500)
    private String correspondenceAddressStreet;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "correspondence_address_building", length = 500)
    private String correspondenceAddressBuilding;

    @Column(name = "correspondence_address_postal_code", length = 10)
    private String correspondenceAddressPostalCode;

    // ── LEW Review Form — Applicant Hint 컬럼 (P1.B, 스펙 §5.3) ──
    // 신청자가 "알면 선택적으로" 입력하는 CoF 관련 힌트. 모두 nullable, CHECK 제약 없음.
    // LEW Review Form Step 2에서 CoF Draft 초기값으로 prefill되는 용도.
    // 형식·범위 오류는 경고 수준(ApplicantHintValidator) — 신청 자체는 저장 후 200 OK.
    // 기존 {@link #spAccountNo}는 legacy 유지, 신규 hint 컬럼과 병행 운영.

    /** MSSL Account No 앞 12자리 암호문(v1:BASE64...) — 신청자 hint. */
    @Convert(converter = com.bluelight.backend.common.crypto.EncryptedStringConverter.class)
    @Column(name = "applicant_mssl_hint_enc", length = 255)
    private String applicantMsslHintEnc;

    /** MSSL Account No 전체의 HMAC-SHA256 검색 해시(64자 hex) — 신청자 hint. */
    @Convert(converter = com.bluelight.backend.common.crypto.HmacStringConverter.class)
    @Column(name = "applicant_mssl_hint_hmac", length = 64)
    private String applicantMsslHintHmac;

    /** MSSL Account No 뒤 4자리 평문 — 마스킹 UI 표시용. */
    @Column(name = "applicant_mssl_hint_last4", length = 4)
    private String applicantMsslHintLast4;

    /** 공급 전압 힌트(V). 형식 무효 시 저장 안 됨(경고). */
    @Column(name = "applicant_supply_voltage_hint")
    private Integer applicantSupplyVoltageHint;

    /** Consumer Type 힌트(Enum 문자열). 형식 무효 시 저장 안 됨. */
    @Column(name = "applicant_consumer_type_hint", length = 20)
    private String applicantConsumerTypeHint;

    /** Retailer 힌트(Enum 문자열). 형식 무효 시 저장 안 됨. */
    @Column(name = "applicant_retailer_hint", length = 32)
    private String applicantRetailerHint;

    /** 발전기 보유 힌트. */
    @Column(name = "applicant_has_generator_hint")
    private Boolean applicantHasGeneratorHint;

    /** 발전기 용량 힌트(kVA). hasGenerator=false여도 저장 허용(경고), LEW finalize에서만 엄격 차단. */
    @Column(name = "applicant_generator_capacity_hint")
    private Integer applicantGeneratorCapacityHint;

    // ── EMA ELISE 제출 추적 (ema-submission-tracking-spec.md §5.2) ──
    // IN_PROGRESS 의 서브-상태 기계. 전이는 본 엔티티 도메인 메서드가 소유한다(상태 기계 캡슐화).
    // ApplicationStatus 에는 값을 추가하지 않는다(NG3) — COMPLETED 로의 단일 전이만 게이팅.

    /**
     * EMA 제출 추적 상태 (기본 {@link EmaSubmissionStatus#NOT_SUBMITTED}).
     * <p>신규 생성 신청은 NOT_SUBMITTED 부터 상태 기계를 탄다. 마이그레이션 backfill 은 오직
     * "도입 시점에 이미 IN_PROGRESS 였던 기존 진행 건"만 APPROVED 로 grandfathering(OQ-1).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "ema_submission_status", nullable = false, length = 30)
    private EmaSubmissionStatus emaSubmissionStatus = EmaSubmissionStatus.NOT_SUBMITTED;

    /** 제출·재제출 시각 (T1/T3/T10 에서 NOW() 로 갱신). */
    @Column(name = "ema_submitted_at")
    private LocalDateTime emaSubmittedAt;

    /** ELISE 접수번호. 설비 행정번호라 PII 아님 → 평문 보관. */
    @Column(name = "ema_reference_no", length = 60)
    private String emaReferenceNo;

    /**
     * 제출 실행 actor(누가 ELISE 에 제출했는지). FK 강제 아님, 값만 보관(기존 {@code *_by} 컨벤션).
     * LEW 본인/ADMIN 대행 구분은 감사로그 actor role 로 보강(§3.2).
     */
    @Column(name = "ema_submitted_by_user_seq")
    private Long emaSubmittedByUserSeq;

    /** APPROVED/REJECTED/WITHDRAWN 결정 시각. 재제출(T3/T10)·Revert(T9) 시 null 로 클리어. */
    @Column(name = "ema_decision_at")
    private LocalDateTime emaDecisionAt;

    /**
     * 질의/반려 사유 (질의·반려 공용, 최신 1건만 보관). 재제출(T3/T10) 시 null 로 클리어(허점#4 —
     * 옛 사유 화면 잔존 방지). 전체 이력은 감사로그로 무손실 추적. 자유 텍스트라 PII 유입 가능
     * → 입력 가이드에 "개인정보 기재 금지" 명시(저장은 평문, OQ-4).
     */
    @Column(name = "ema_query_note", length = 1000)
    private String emaQueryNote;

    /**
     * 결정(APPROVED/REJECTED/WITHDRAWN) 직전 상태 보관 — Revert(T9) 복원 슬롯(허점#1).
     * <p>approve/reject/withdraw(T5~T8) 진입 시 직전 from 상태를 저장 → T9 가 정확 복원.
     * 복원·재제출 시 null 로 클리어. 1-depth 복원만 — 다단계 undo 는 비목표.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "ema_status_before_decision", length = 30)
    private EmaSubmissionStatus emaStatusBeforeDecision;

    /**
     * EMA 제출 리마인더 발송 시각 — 중복 발송 가드(PR-E5, {@link #markExpiryNotified} 패턴 동일).
     * <p>스케줄러가 "1일 1회 멱등"으로 발송한 뒤 NOW() 를 기록한다. 모든 EMA 전이 시 null 로 리셋해,
     * 새 SUBMITTED/RESUBMITTED 구간이 시작되면 리마인더가 다시 발화할 수 있게 한다.
     */
    @Column(name = "ema_reminder_notified_at")
    private LocalDateTime emaReminderNotifiedAt;

    @Builder
    public Application(User user, String address, String postalCode, String buildingType,
                       Integer selectedKva, BigDecimal quoteAmount, BigDecimal sldFee,
                       BigDecimal calloutFee,
                       String spAccountNo, SldOption sldOption,
                       ApplicationType applicationType, ApplicantType applicantType,
                       Application originalApplication,
                       String existingLicenceNo, String renewalReferenceNo,
                       LocalDate existingExpiryDate, Integer renewalPeriodMonths,
                       BigDecimal emaFee,
                       KvaStatus kvaStatus, KvaSource kvaSource,
                       Long viaConciergeRequestSeq,
                       // ── P1.1: EMA ELISE 필드 (기존 빌더 호출부는 이 파라미터를 생략 가능 — 모두 null 허용) ──
                       String installationName,
                       PremisesType premisesType,
                       Boolean isRentalPremises,
                       String landlordEiLicenceNo,
                       Boolean renewalCompanyNameChanged,
                       Boolean renewalAddressChanged,
                       String installationAddressBlock,
                       String installationAddressUnit,
                       String installationAddressStreet,
                       String installationAddressBuilding,
                       String installationAddressPostalCode,
                       String correspondenceAddressBlock,
                       String correspondenceAddressUnit,
                       String correspondenceAddressStreet,
                       String correspondenceAddressBuilding,
                       String correspondenceAddressPostalCode) {
        this.user = user;
        this.address = address;
        this.postalCode = postalCode;
        this.buildingType = buildingType;
        this.selectedKva = selectedKva;
        this.quoteAmount = quoteAmount;
        this.sldFee = sldFee;
        this.calloutFee = calloutFee;
        this.spAccountNo = spAccountNo;
        this.sldOption = sldOption != null ? sldOption : SldOption.SELF_UPLOAD;
        this.applicationType = applicationType != null ? applicationType : ApplicationType.NEW;
        this.applicantType = applicantType != null ? applicantType : ApplicantType.INDIVIDUAL;
        this.originalApplication = originalApplication;
        this.existingLicenceNo = existingLicenceNo;
        this.renewalReferenceNo = renewalReferenceNo;
        this.existingExpiryDate = existingExpiryDate;
        this.renewalPeriodMonths = renewalPeriodMonths;
        this.emaFee = emaFee;
        this.status = ApplicationStatus.PENDING_REVIEW;
        // Phase 5: kVA 상태 (기본값은 필드 초기화로 CONFIRMED — 하위호환)
        this.kvaStatus = kvaStatus != null ? kvaStatus : KvaStatus.CONFIRMED;
        this.kvaSource = kvaSource;
        // ★ PR#5 Stage A: Concierge 대리 생성 연결 (null이면 APPLICANT 직접 신청)
        this.viaConciergeRequestSeq = viaConciergeRequestSeq;
        // EMA ELISE 필드 — 모두 nullable (기존 호출부는 생략해도 Lombok Builder가 null 주입)
        this.installationName = installationName;
        this.premisesType = premisesType;
        this.isRentalPremises = isRentalPremises;
        this.landlordEiLicenceNo = landlordEiLicenceNo;
        this.renewalCompanyNameChanged = renewalCompanyNameChanged;
        this.renewalAddressChanged = renewalAddressChanged;
        this.installationAddressBlock = installationAddressBlock;
        this.installationAddressUnit = installationAddressUnit;
        this.installationAddressStreet = installationAddressStreet;
        this.installationAddressBuilding = installationAddressBuilding;
        this.installationAddressPostalCode = installationAddressPostalCode;
        this.correspondenceAddressBlock = correspondenceAddressBlock;
        this.correspondenceAddressUnit = correspondenceAddressUnit;
        this.correspondenceAddressStreet = correspondenceAddressStreet;
        this.correspondenceAddressBuilding = correspondenceAddressBuilding;
        this.correspondenceAddressPostalCode = correspondenceAddressPostalCode;
    }

    /**
     * 상태 변경
     */
    public void changeStatus(ApplicationStatus status) {
        this.status = status;
    }

    /**
     * LEW 보완 요청
     */
    public void requestRevision(String comment) {
        this.reviewComment = comment;
        this.status = ApplicationStatus.REVISION_REQUESTED;
    }

    /**
     * 신청자 보완 후 재제출
     */
    public void resubmit() {
        this.status = ApplicationStatus.PENDING_REVIEW;
    }

    /**
     * LEW 검토 승인 → 결제 요청
     */
    public void approveForPayment() {
        this.reviewComment = null;
        this.status = ApplicationStatus.PENDING_PAYMENT;
    }

    /**
     * 신청 내용 수정 (보완 시).
     *
     * <p><b>Phase 5 보안 가드 (재제출 허점 차단)</b>: 이미 {@code kvaStatus=CONFIRMED} 인 신청에서
     * 신청자가 재제출(REVISION_REQUESTED → PENDING_REVIEW) 시 {@code selectedKva} 를
     * 임의로 변경해 가격을 우회하는 경로를 차단한다.
     * <ul>
     *   <li>{@code kvaStatus=CONFIRMED} 이면 {@code selectedKva}/{@code quoteAmount}/{@code sldFee}
     *       파라미터를 <b>무시</b>하고 기존 값을 유지한다. 주소/우편번호/건물유형만 갱신.</li>
     *   <li>{@code kvaStatus=UNKNOWN} 이면 기존처럼 모두 갱신 가능 (아직 확정 전).</li>
     * </ul>
     * 출처: {@code phase5-kva-ux/03-security-review.md} §1.1, 추가 발견 — 사용자 결정:
     * "LEW 확정 후에는 LEW만 수정 가능".
     */
    public void updateDetails(String address, String postalCode, String buildingType,
                              Integer selectedKva, BigDecimal quoteAmount, BigDecimal sldFee,
                              BigDecimal calloutFee) {
        this.address = address;
        this.postalCode = postalCode;
        this.buildingType = buildingType;
        if (this.kvaStatus == KvaStatus.CONFIRMED) {
            // CONFIRMED 인 경우 kVA/금액 재계산은 applicant 가 수행 불가 — 기존값 유지
            return;
        }
        this.selectedKva = selectedKva;
        this.quoteAmount = quoteAmount;
        this.sldFee = sldFee;
        this.calloutFee = calloutFee;
    }

    /**
     * 출장비(call-out fee) 스냅샷 단독 갱신.
     * <p>{@link #confirmKva}/post-payment 재계산은 quoteAmount 만 바꾸므로, 그 경로에서
     * quoteAmount 에 가산한 출장비 값을 이 필드에도 반영해 견적 분해 표시 정합성을 유지한다.</p>
     */
    public void reflectCalloutFee(BigDecimal calloutFee) {
        this.calloutFee = calloutFee;
    }

    /**
     * Installation Address 5-part 갱신 — EMA ELISE 양식 대응.
     * <p>재제출 시 신청자가 Block/Unit/Street/Building/PostalCode 각각을 정정할 수 있다.
     * null 값은 "해당 서브필드를 지움" 의미 (UpdateApplicationRequest 로부터 일괄 덮어쓰기).</p>
     * <p>legacy {@code address}/{@code postalCode} 는 컨트롤러·프론트에서 concat 하여
     * {@link #updateDetails} 로 동시에 갱신되므로, 본 메서드는 5-part 컬럼만 관리.</p>
     */
    public void updateInstallationAddressParts(String block, String unit, String street,
                                               String building, String postalCode) {
        this.installationAddressBlock = block;
        this.installationAddressUnit = unit;
        this.installationAddressStreet = street;
        this.installationAddressBuilding = building;
        this.installationAddressPostalCode = postalCode;
    }

    /**
     * Correspondence Address 5-part 갱신. Installation 과 동일한 정책.
     * 모든 인자 null 이면 "Correspondence = Installation" 으로 해석 (LoA 생성 경로가 이미 이렇게 처리).
     */
    public void updateCorrespondenceAddressParts(String block, String unit, String street,
                                                 String building, String postalCode) {
        this.correspondenceAddressBlock = block;
        this.correspondenceAddressUnit = unit;
        this.correspondenceAddressStreet = street;
        this.correspondenceAddressBuilding = building;
        this.correspondenceAddressPostalCode = postalCode;
    }

    // ── LEW Review Form — Applicant Hint 도메인 메서드 (P1.B, 스펙 §5.3) ──

    /**
     * 신청자 힌트(CoF 관련 prefill 정보)를 일괄 갱신한다.
     * <p>모든 인자는 nullable. null은 "변경하지 않음"이 아니라 "해당 필드를 null로 세팅"을 의미한다
     * (서비스 레이어의 {@code ApplicantHintValidator}가 경고 수준으로 정상화한 후 호출).</p>
     *
     * <p>MSSL 3종(enc/hmac/last4)는 평문을 그대로 받지 않고 서비스에서 분리해서 전달한다
     * (평문을 DB까지 가져가지 않는 원칙 — {@code EncryptedStringConverter}가 저장 직전
     * 컬럼 단위에서만 암호화하므로, 엔티티 속성에 평문을 두면 메모리에 잔존).</p>
     */
    public void updateApplicantHints(String msslHintEnc,
                                     String msslHintHmac,
                                     String msslHintLast4,
                                     Integer supplyVoltageHint,
                                     String consumerTypeHint,
                                     String retailerHint,
                                     Boolean hasGeneratorHint,
                                     Integer generatorCapacityHint) {
        this.applicantMsslHintEnc = msslHintEnc;
        this.applicantMsslHintHmac = msslHintHmac;
        this.applicantMsslHintLast4 = msslHintLast4;
        this.applicantSupplyVoltageHint = supplyVoltageHint;
        this.applicantConsumerTypeHint = consumerTypeHint;
        this.applicantRetailerHint = retailerHint;
        this.applicantHasGeneratorHint = hasGeneratorHint;
        this.applicantGeneratorCapacityHint = generatorCapacityHint;
    }

    // ── Phase 5: kVA 확정 도메인 메서드 ──

    /**
     * LEW/ADMIN 에 의한 kVA 확정 (Phase 5).
     *
     * <p>상태 전이 규칙:
     * <ul>
     *   <li>{@code kvaStatus=UNKNOWN} → {@code CONFIRMED} 로 전환.</li>
     *   <li>{@code kvaStatus=CONFIRMED} 인 경우에도 결제 전에는 재확정(값 변경 포함)을 허용한다.
     *       신청자 입력값(USER_INPUT) 검토 확정, LEW 확정 후 재변경 모두 동일 경로.</li>
     *   <li>재계산된 {@code quoteAmount} 는 서비스에서 계산 후 파라미터로 전달.</li>
     * </ul>
     *
     * <p>금지 상태 검증({@code PAID} 이후 차단, B-3)과 권한(배정 LEW/ADMIN)·정책은 서비스에서 수행 —
     * 도메인에서는 kvaStatus/값 전이만 관리한다.
     *
     * @param selectedKva    새 kVA tier
     * @param quoteAmount    재계산된 금액
     * @param confirmedBy    확정자 (LEW 또는 ADMIN)
     * @param force          ADMIN 명시적 override 여부 — 감사 라벨 구분용(서비스에서 사용). 도메인 전이엔
     *                       영향 없음(결제 전 재확정은 force 무관 허용).
     */
    public void confirmKva(Integer selectedKva, BigDecimal quoteAmount,
                           User confirmedBy, boolean force) {
        this.selectedKva = selectedKva;
        this.quoteAmount = quoteAmount;
        this.kvaStatus = KvaStatus.CONFIRMED;
        this.kvaSource = KvaSource.LEW_VERIFIED;
        this.kvaConfirmedBy = confirmedBy;
        this.kvaConfirmedAt = LocalDateTime.now();
    }

    /**
     * 결제 후 kVA 사후 변경 도메인 메서드.
     *
     * <p>스펙: {@code doc/Project Analysis/kva-postpayment-adjustment-spec.md} §3, §6.1.
     * ADMIN 이 결제 완료 이후 단계({@link ApplicationStatus#PAID}/{@link ApplicationStatus#IN_PROGRESS}/
     * {@link ApplicationStatus#COMPLETED}) 에서만 호출 가능. EXPIRED 상태는 서비스 레이어에서 차단.</p>
     *
     * <h3>상태 전이 규칙</h3>
     * <ul>
     *   <li>{@code Application.status} 는 변경하지 않는다 (PR3 모델 — CoF 는 결제 후 단계이므로 reopen 불필요).</li>
     *   <li>{@code kvaStatus} 는 {@link KvaStatus#CONFIRMED} 그대로 유지.</li>
     *   <li>{@code kvaSource} 는 변경하지 않는다 — 최초 확정 시 출처를 보존하기 위함
     *       (변경 이력은 {@code KvaAdjustmentRecord} 에서 추적).</li>
     *   <li>{@code selectedKva}/{@code quoteAmount} 갱신, {@code kvaConfirmedBy}/{@code kvaConfirmedAt}
     *       는 ADMIN override 자로 갱신.</li>
     * </ul>
     *
     * @throws IllegalStateException 호출 시점 status 가 결제 후 허용 상태가 아닌 경우
     * @throws IllegalArgumentException newKva/newQuote 인자가 null 인 경우
     */
    public void overrideKvaPostPayment(Integer newKva, BigDecimal newQuoteAmount, User overrider) {
        if (newKva == null) {
            throw new IllegalArgumentException("newKva must not be null");
        }
        if (newQuoteAmount == null) {
            throw new IllegalArgumentException("newQuoteAmount must not be null");
        }
        if (!isPostPaymentStatus()) {
            throw new IllegalStateException(
                    "overrideKvaPostPayment is only allowed for post-payment statuses (current: "
                            + this.status + ")");
        }
        this.selectedKva = newKva;
        this.quoteAmount = newQuoteAmount;
        // kvaStatus 는 CONFIRMED 그대로, kvaSource 는 보존. 변경 이력은 KvaAdjustmentRecord 에서 추적.
        this.kvaConfirmedBy = overrider;
        this.kvaConfirmedAt = LocalDateTime.now();
    }

    /**
     * 본 신청이 결제 후 단계인지 (PAID/IN_PROGRESS/COMPLETED).
     * EXPIRED 는 본 메서드에서 false — 결제 자체가 closed 된 상태.
     */
    public boolean isPostPaymentStatus() {
        return this.status == ApplicationStatus.PAID
                || this.status == ApplicationStatus.IN_PROGRESS
                || this.status == ApplicationStatus.COMPLETED;
    }

    /**
     * SLD self-upload/3개월유예 → LEW 작성(REQUEST_LEW) 전환 + SLD 작성비 가산.
     *
     * <p>spec: {@code doc/Project Analysis/sld-lew-conversion-fee-spec.md} §3.1.
     * 신청자가 SLD 를 직접 제출하기로 했으나 미제공/무효일 때 LEW 가 작성을 떠맡고 SLD 작성비를 청구한다.
     * {@code status}/{@code kvaStatus}/{@code kvaSource} 는 변경하지 않는다 — 전환은 검토·결제후
     * 어느 단계에서도 가능하며 변경 이력·정산은 {@code KvaAdjustmentRecord(adjustment_type=SLD_ADDED)}
     * 에서 추적한다. 행위자는 서비스가 원장에 기록한다.</p>
     *
     * @param sldFee         전환 시점 master_prices 스냅샷 (SGD)
     * @param newQuoteAmount sldFee 가 가산된 재계산 견적
     * @throws IllegalStateException    이미 REQUEST_LEW 인 경우
     * @throws IllegalArgumentException 인자가 null 인 경우
     */
    public void switchSldToLewCreated(BigDecimal sldFee, BigDecimal newQuoteAmount) {
        if (sldFee == null || newQuoteAmount == null) {
            throw new IllegalArgumentException("sldFee and newQuoteAmount must not be null");
        }
        if (this.sldOption == SldOption.REQUEST_LEW) {
            throw new IllegalStateException("SLD is already LEW-created (sldOption=REQUEST_LEW)");
        }
        this.sldOption = SldOption.REQUEST_LEW;
        this.sldFee = sldFee;
        this.quoteAmount = newQuoteAmount;
    }

    /**
     * SP 계정 번호 수정
     */
    public void updateSpAccountNo(String spAccountNo) {
        this.spAccountNo = spAccountNo;
    }

    /**
     * 갱신 기간 수정 (Admin/LEW)
     */
    public void updateRenewalPeriod(Integer renewalPeriodMonths, BigDecimal emaFee) {
        this.renewalPeriodMonths = renewalPeriodMonths;
        this.emaFee = emaFee;
    }

    /**
     * 결제 완료 처리
     */
    public void markAsPaid() {
        this.status = ApplicationStatus.PAID;
    }

    /**
     * 점검 시작
     */
    public void startInspection() {
        this.status = ApplicationStatus.IN_PROGRESS;
    }

    /**
     * 라이선스 발급
     */
    public void issueLicense(String licenseNumber, LocalDate expiryDate) {
        this.licenseNumber = licenseNumber;
        this.licenseExpiryDate = expiryDate;
        this.status = ApplicationStatus.COMPLETED;
    }

    /**
     * 만료 처리
     */
    public void markAsExpired() {
        this.status = ApplicationStatus.EXPIRED;
    }

    /**
     * 만료 알림 발송 기록
     */
    public void markExpiryNotified() {
        this.expiryNotifiedAt = LocalDateTime.now();
    }

    /**
     * LEW 할당
     */
    public void assignLew(User lew) {
        this.assignedLew = lew;
    }

    /**
     * LEW 할당 해제
     */
    public void unassignLew() {
        this.assignedLew = null;
    }

    /**
     * LOA 전자서명 등록 (레거시 — 디지털 서명 모델, 신규 동선 미사용)
     */
    public void registerLoaSignature(String signatureUrl) {
        this.loaSignatureUrl = signatureUrl;
        this.loaSignedAt = LocalDateTime.now();
    }

    // ── LoA 교환 모델 전이 (loa-exchange 재설계 PR3) ──

    /** (NEW) LEW가 LoA 폼 전달: active 폼 버전 스냅샷 고정 + 단계 FORM_SENT. */
    public void markLoaFormSent(Long formTemplateSeq) {
        this.loaFormTemplateSeq = formTemplateSeq;
        if (this.loaStage == LoaStage.NOT_STARTED) {
            this.loaStage = LoaStage.FORM_SENT;
        }
    }

    /** 신청자가 서명본 업로드: 단계 APPLICANT_UPLOADED (이미 FINAL이면 되돌리지 않음). */
    public void markLoaApplicantUploaded() {
        if (this.loaStage == LoaStage.NOT_STARTED || this.loaStage == LoaStage.FORM_SENT) {
            this.loaStage = LoaStage.APPLICANT_UPLOADED;
        }
    }

    /** LEW가 최종본 업로드: 단계 FINAL_UPLOADED. */
    public void markLoaFinalUploaded() {
        this.loaStage = LoaStage.FINAL_UPLOADED;
    }

    /** 결제 요청 가능 여부 — LoA 수령(신청자 업로드 이상). NEW/RENEWAL 공통(D-1). */
    public boolean isLoaReceivedForPayment() {
        return this.loaStage == LoaStage.APPLICANT_UPLOADED
                || this.loaStage == LoaStage.FINAL_UPLOADED;
    }

    /** 결제 요청 가능 여부(강화) — LEW 최종본 업로드까지 완료(FINAL_UPLOADED)되어야 결제 요청 가능. */
    public boolean isLoaFinalized() {
        return this.loaStage == LoaStage.FINAL_UPLOADED;
    }

    /**
     * LOA 생성 시점의 신청자 신원 스냅샷 기록 (Phase 2 PR#4 / B-5).
     * <p>
     * 최초 1회만 채우며, 이후 호출해도 {@code @Column(updatable=false)}로 인해
     * UPDATE 시 무시된다 (영속화 레이어 차단). 도메인 레벨에서도 기존 값이
     * 있으면 재기록하지 않는다(재발급 시 LOA 파일 regenerate는 스냅샷을 바꾸지
     * 않는다 — 원본 스냅샷 유지가 법적 무결성 원칙).
     * <p>
     * {@code snapshotBackfilledAt}는 항상 null로 설정 — 이 메서드는 실시간 생성 경로이므로
     * 백필이 아님을 명시한다.
     *
     * <p>4-arg 시그니처는 {@link #recordLoaSnapshot(String, String, String, String, String, String)}의 래퍼로
     * 유지된다 (LEW/Admin 경로 호환성). phone/email 은 null 로 전달.
     *
     * @return true: 신규 기록됨, false: 이미 스냅샷이 존재해 건너뜀
     */
    public boolean recordLoaSnapshot(String applicantName, String companyName,
                                     String uen, String designation) {
        return recordLoaSnapshot(applicantName, companyName, uen, designation, null, null);
    }

    /**
     * LOA 스냅샷 + phone/email 통합 기록 (C.1 Snapshot-at-submit).
     * <p>
     * 신청 Submit 시점에 Application을 "신청 당시 정본"으로 격상하기 위한 확장 시그니처.
     * phone/email 컬럼도 {@code @Column(updatable=false)}이므로 동일한 불변 정책을 따른다.
     *
     * @return true: 신규 기록됨, false: 이미 스냅샷이 존재해 건너뜀(멱등)
     */
    public boolean recordLoaSnapshot(String applicantName, String companyName,
                                     String uen, String designation,
                                     String phone, String email) {
        if (this.loaApplicantNameSnapshot != null && !this.loaApplicantNameSnapshot.isBlank()) {
            return false;
        }
        this.loaApplicantNameSnapshot = applicantName != null ? applicantName : "";
        this.loaCompanyNameSnapshot = companyName;
        this.loaUenSnapshot = uen;
        this.loaDesignationSnapshot = designation;
        this.loaPhoneSnapshot = phone;
        this.loaEmailSnapshot = email;
        this.loaSnapshotBackfilledAt = null;
        return true;
    }

    // ── LOA 서명 출처 도메인 메서드 (★ Kaki Concierge v1.5, Phase 1 PR#1 Stage 3) ──

    /**
     * LOA 서명 출처 기록 (경로 A/B 공통, 최초 1회만).
     * <p>
     * Phase 1: MANAGER_UPLOAD 경로에서 {@code LoaService.uploadSignature} 호출 시 사용.
     * APPLICANT_DIRECT 경로는 기존 {@link #registerLoaSignature(String)}와 병행 호출하거나
     * Service 레이어에서 함께 호출하는 방식을 택한다 (이번 Stage에서는 엔티티 메서드만 제공).
     * <p>
     * 재호출 방지: 이미 source가 기록되어 있으면 동일 source 호출은 멱등(false 반환),
     * 다른 source로의 덮어쓰기는 {@link IllegalStateException}. {@code @Column(updatable=false)}가
     * JPA 레벨 가드를 제공하지만 도메인 레벨에서도 명시적으로 차단한다.
     * <p>
     * {@link #loaSignatureUploadedBy}는 Manager 엔티티 resolving 이후
     * {@link #setLoaSignatureUploadedBy(User)}로 별도 세팅 (연관관계 주입).
     *
     * @param source             LOA 서명 출처 (null 금지)
     * @param uploadedByUserSeq  업로드한 Manager의 userSeq (로깅/감사용 참고 파라미터, 엔티티 연결은 별도 메서드)
     * @param memo               수령 경로 메모 (nullable)
     * @return true: 신규 기록됨, false: 이미 동일 source로 기록되어 건너뜀(멱등)
     * @throws IllegalArgumentException source가 null인 경우
     * @throws IllegalStateException    이미 다른 source가 기록된 경우
     */
    public boolean recordLoaSignatureSource(LoaSignatureSource source, Long uploadedByUserSeq, String memo) {
        if (source == null) {
            throw new IllegalArgumentException("LOA signature source must not be null");
        }
        if (this.loaSignatureSource != null) {
            if (this.loaSignatureSource != source) {
                throw new IllegalStateException(
                    "LOA signature source already recorded as " + this.loaSignatureSource
                        + ", cannot change to " + source);
            }
            return false; // 멱등: 동일 source 재호출
        }
        this.loaSignatureSource = source;
        this.loaSignatureUploadedAt = LocalDateTime.now();
        this.loaSignatureSourceMemo = memo;
        // loaSignatureUploadedBy는 setLoaSignatureUploadedBy(User)에서 별도 세팅
        return true;
    }

    /**
     * LOA 서명 업로더(Manager) 연결. {@link #recordLoaSignatureSource} 이후에만 호출 가능.
     * APPLICANT_DIRECT 경로에서는 호출하지 않는다.
     *
     * @throws IllegalStateException recordLoaSignatureSource 호출 전 / APPLICANT_DIRECT 상태 / 이미 세팅됨
     */
    public void setLoaSignatureUploadedBy(User uploader) {
        if (this.loaSignatureSource == null) {
            throw new IllegalStateException("recordLoaSignatureSource must be called first");
        }
        if (this.loaSignatureSource == LoaSignatureSource.APPLICANT_DIRECT) {
            throw new IllegalStateException("APPLICANT_DIRECT source cannot have uploader");
        }
        if (this.loaSignatureUploadedBy != null) {
            throw new IllegalStateException("LOA signature uploader already set");
        }
        this.loaSignatureUploadedBy = uploader;
    }

    // ── Concierge 대리 생성 판정 (★ PR#5 Stage A) ──

    /**
     * 이 신청이 Concierge Manager에 의한 대리 생성인지 여부.
     */
    public boolean isCreatedViaConcierge() {
        return viaConciergeRequestSeq != null;
    }

    // ── EMA ELISE 제출 추적 상태 기계 (ema-submission-tracking-spec.md §3 전이표 T1~T10) ──
    // 상태 기계를 엔티티가 소유한다. 각 메서드는 from-state 가드 + 부수효과(타임스탬프/슬롯
    // 저장/클리어)를 엔티티 내부에서 수행하며, 잘못된 전이는 BusinessException(BAD_REQUEST,
    // "INVALID_EMA_TRANSITION") 으로 거부한다. 권한 SpEL / 접수번호·queryNote 필수 검증 /
    // EMA_ACK 첨부 검증 / 감사 기록은 서비스 레이어 책임(컨트롤러 SpEL 은 PR-E2).
    // App.status==IN_PROGRESS 게이트도 본 메서드 진입 직전 서비스에서 검증한다(§3.2 NG3).

    /**
     * T1: {@code NOT_SUBMITTED → SUBMITTED}. ELISE 제출 사실 기록.
     *
     * @param referenceNo  ELISE 접수번호 (서비스에서 @NotBlank 검증 완료된 값)
     * @param actorSeq     제출 실행 actor userSeq (영속 보관 — 누가 ELISE 에 제출했는지)
     * @throws BusinessException from-state 가 NOT_SUBMITTED 가 아닌 경우
     */
    public void markEmaSubmitted(String referenceNo, Long actorSeq) {
        if (this.emaSubmissionStatus != EmaSubmissionStatus.NOT_SUBMITTED) {
            throw invalidEmaTransition("markEmaSubmitted");
        }
        this.emaSubmissionStatus = EmaSubmissionStatus.SUBMITTED;
        this.emaReferenceNo = referenceNo;
        this.emaSubmittedAt = LocalDateTime.now();
        this.emaSubmittedByUserSeq = actorSeq;
        this.emaReminderNotifiedAt = null; // 새 SUBMITTED 구간 — 리마인더 재발화 허용
    }

    /**
     * T2/T4: {@code SUBMITTED/RESUBMITTED → QUERY_RAISED}. EMA 질의 기록.
     *
     * @param queryNote  질의 내용 (서비스에서 @NotBlank 검증 완료된 값)
     * @throws BusinessException from-state 가 SUBMITTED/RESUBMITTED 가 아닌 경우
     */
    public void raiseEmaQuery(String queryNote) {
        if (this.emaSubmissionStatus != EmaSubmissionStatus.SUBMITTED
                && this.emaSubmissionStatus != EmaSubmissionStatus.RESUBMITTED) {
            throw invalidEmaTransition("raiseEmaQuery");
        }
        this.emaSubmissionStatus = EmaSubmissionStatus.QUERY_RAISED;
        this.emaQueryNote = queryNote;
        this.emaReminderNotifiedAt = null; // 리마인더 타이머 리셋(LEW 가 보완해야 함)
    }

    /**
     * T3/T10: {@code QUERY_RAISED/REJECTED → RESUBMITTED}. 보완 후 재제출.
     *
     * <p>부수효과: 재제출 시각 갱신 + 직전 결정·사유·복원 슬롯 클리어(허점#4).
     * {@code emaQueryNote=null}(옛 질의/반려 사유 잔존 방지), {@code emaDecisionAt=null},
     * {@code emaStatusBeforeDecision=null}. 전체 이력은 감사로그로 무손실 추적.
     *
     * @param referenceNo  갱신된 접수번호 (선택 — null/blank 면 기존 값 유지)
     * @param actorSeq     재제출 실행 actor userSeq
     * @throws BusinessException from-state 가 QUERY_RAISED/REJECTED 가 아닌 경우
     */
    public void resubmitEma(String referenceNo, Long actorSeq) {
        if (this.emaSubmissionStatus != EmaSubmissionStatus.QUERY_RAISED
                && this.emaSubmissionStatus != EmaSubmissionStatus.REJECTED) {
            throw invalidEmaTransition("resubmitEma");
        }
        this.emaSubmissionStatus = EmaSubmissionStatus.RESUBMITTED;
        if (referenceNo != null && !referenceNo.isBlank()) {
            this.emaReferenceNo = referenceNo;
        }
        this.emaSubmittedAt = LocalDateTime.now();
        this.emaSubmittedByUserSeq = actorSeq;
        // 직전 결정·사유·복원 슬롯 클리어 (허점#4)
        this.emaQueryNote = null;
        this.emaDecisionAt = null;
        this.emaStatusBeforeDecision = null;
        this.emaReminderNotifiedAt = null; // 재제출 — 새 RESUBMITTED 구간 리마인더 재시작
    }

    /**
     * T5/T6: {@code SUBMITTED/RESUBMITTED → APPROVED}. EMA 승인 표기(발급과 분리 — §3.3/§4.2).
     *
     * <p>전이 직전 from 상태를 {@link #emaStatusBeforeDecision} 에 저장해 Revert(T9)가 정확
     * 복원할 수 있게 한다(허점#1).
     *
     * @throws BusinessException from-state 가 SUBMITTED/RESUBMITTED 가 아닌 경우
     */
    public void approveEma() {
        if (this.emaSubmissionStatus != EmaSubmissionStatus.SUBMITTED
                && this.emaSubmissionStatus != EmaSubmissionStatus.RESUBMITTED) {
            throw invalidEmaTransition("approveEma");
        }
        this.emaStatusBeforeDecision = this.emaSubmissionStatus;
        this.emaSubmissionStatus = EmaSubmissionStatus.APPROVED;
        this.emaDecisionAt = LocalDateTime.now();
        this.emaReminderNotifiedAt = null; // 결정됨 — 리마인더 타이머 종료
    }

    /**
     * T7: {@code SUBMITTED/RESUBMITTED → REJECTED}. EMA 반려(종착 아님 — T10 재진입 가능).
     *
     * <p>App.status 는 IN_PROGRESS 유지(서비스 책임). 전이 직전 from 상태를 슬롯에 저장(허점#1).
     *
     * @param reason  반려 사유 (선택 — null/blank 면 기존 queryNote 유지)
     * @throws BusinessException from-state 가 SUBMITTED/RESUBMITTED 가 아닌 경우
     */
    public void rejectEma(String reason) {
        if (this.emaSubmissionStatus != EmaSubmissionStatus.SUBMITTED
                && this.emaSubmissionStatus != EmaSubmissionStatus.RESUBMITTED) {
            throw invalidEmaTransition("rejectEma");
        }
        this.emaStatusBeforeDecision = this.emaSubmissionStatus;
        this.emaSubmissionStatus = EmaSubmissionStatus.REJECTED;
        this.emaDecisionAt = LocalDateTime.now();
        this.emaReminderNotifiedAt = null; // 결정됨 — 리마인더 타이머 종료
        if (reason != null && !reason.isBlank()) {
            this.emaQueryNote = reason;
        }
    }

    /**
     * T8: {@code SUBMITTED/QUERY_RAISED/RESUBMITTED → WITHDRAWN}. EMA 철회(종착).
     *
     * <p>전이 직전 from 상태를 슬롯에 저장 → ADMIN Revert(T9)로만 복원 가능(허점#1).
     *
     * @throws BusinessException from-state 가 SUBMITTED/QUERY_RAISED/RESUBMITTED 가 아닌 경우
     */
    public void withdrawEma() {
        if (this.emaSubmissionStatus != EmaSubmissionStatus.SUBMITTED
                && this.emaSubmissionStatus != EmaSubmissionStatus.QUERY_RAISED
                && this.emaSubmissionStatus != EmaSubmissionStatus.RESUBMITTED) {
            throw invalidEmaTransition("withdrawEma");
        }
        this.emaStatusBeforeDecision = this.emaSubmissionStatus;
        this.emaSubmissionStatus = EmaSubmissionStatus.WITHDRAWN;
        this.emaDecisionAt = LocalDateTime.now();
        this.emaReminderNotifiedAt = null; // 결정됨 — 리마인더 타이머 종료
    }

    /**
     * T9: {@code APPROVED/WITHDRAWN → 직전 상태}. ADMIN 오기입 정정(컨트롤러 SpEL 로 LEW 제외).
     *
     * <p>{@link #emaStatusBeforeDecision} 으로 정확 복원 → 복원 후 슬롯·결정시각 null 클리어.
     * 슬롯이 null 이면(grandfathered APPROVED 등) {@link EmaSubmissionStatus#SUBMITTED} 폴백(허점#1).
     *
     * @throws BusinessException from-state 가 APPROVED/WITHDRAWN 가 아닌 경우
     */
    public void revertEmaDecision() {
        if (this.emaSubmissionStatus != EmaSubmissionStatus.APPROVED
                && this.emaSubmissionStatus != EmaSubmissionStatus.WITHDRAWN) {
            throw invalidEmaTransition("revertEmaDecision");
        }
        this.emaSubmissionStatus = this.emaStatusBeforeDecision != null
                ? this.emaStatusBeforeDecision
                : EmaSubmissionStatus.SUBMITTED; // null 폴백 (grandfathered 등)
        this.emaStatusBeforeDecision = null;
        this.emaDecisionAt = null;
        this.emaReminderNotifiedAt = null; // 복원됨 — SUBMITTED/RESUBMITTED 로 돌아가면 리마인더 재발화
    }

    /** 잘못된 EMA 전이 — 컨트롤러/GlobalExceptionHandler 가 400 으로 변환. */
    private BusinessException invalidEmaTransition(String action) {
        return new BusinessException(
                "Invalid EMA transition: " + action + " is not allowed from " + this.emaSubmissionStatus,
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "INVALID_EMA_TRANSITION");
    }

    /**
     * EMA 제출 리마인더 발송 기록 (PR-E5, {@link #markExpiryNotified} 패턴 동일).
     * 스케줄러가 인앱 리마인더를 보낸 뒤 호출 → 같은 날 재발송을 멱등 차단.
     */
    public void markEmaReminderNotified() {
        this.emaReminderNotifiedAt = LocalDateTime.now();
    }
}

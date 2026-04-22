package com.bluelight.backend.domain.application;

import com.bluelight.backend.common.crypto.EncryptedStringConverter;
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

    @Builder
    public Application(User user, String address, String postalCode, String buildingType,
                       Integer selectedKva, BigDecimal quoteAmount, BigDecimal sldFee,
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
                              Integer selectedKva, BigDecimal quoteAmount, BigDecimal sldFee) {
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
    }

    // ── Phase 5: kVA 확정 도메인 메서드 ──

    /**
     * LEW/ADMIN 에 의한 kVA 확정 (Phase 5).
     *
     * <p>상태 전이 규칙:
     * <ul>
     *   <li>{@code kvaStatus=UNKNOWN} → {@code CONFIRMED} 로 전환.</li>
     *   <li>{@code kvaStatus=CONFIRMED} 인 경우 {@code force=false} 이면 {@link IllegalStateException}.
     *       컨트롤러/서비스에서 409 {@code KVA_ALREADY_CONFIRMED} 로 변환할 것.</li>
     *   <li>재계산된 {@code quoteAmount} 는 서비스에서 계산 후 파라미터로 전달.</li>
     * </ul>
     *
     * <p>금지 상태 검증({@code PAID} 이후 차단, B-3)은 서비스에서 수행 — 도메인에서는
     * kvaStatus 자체의 전이만 관리한다.
     *
     * @param selectedKva    새 kVA tier
     * @param quoteAmount    재계산된 금액
     * @param confirmedBy    확정자 (LEW 또는 ADMIN)
     * @param force          이미 CONFIRMED 상태에서 덮어쓸지 여부 (ADMIN 전용, 컨트롤러에서 역할 검증)
     */
    public void confirmKva(Integer selectedKva, BigDecimal quoteAmount,
                           User confirmedBy, boolean force) {
        if (this.kvaStatus == KvaStatus.CONFIRMED && !force) {
            throw new IllegalStateException("kVA is already confirmed");
        }
        this.selectedKva = selectedKva;
        this.quoteAmount = quoteAmount;
        this.kvaStatus = KvaStatus.CONFIRMED;
        this.kvaSource = KvaSource.LEW_VERIFIED;
        this.kvaConfirmedBy = confirmedBy;
        this.kvaConfirmedAt = LocalDateTime.now();
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
     * LOA 전자서명 등록
     */
    public void registerLoaSignature(String signatureUrl) {
        this.loaSignatureUrl = signatureUrl;
        this.loaSignedAt = LocalDateTime.now();
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
     * @return true: 신규 기록됨, false: 이미 스냅샷이 존재해 건너뜀
     */
    public boolean recordLoaSnapshot(String applicantName, String companyName,
                                     String uen, String designation) {
        if (this.loaApplicantNameSnapshot != null && !this.loaApplicantNameSnapshot.isBlank()) {
            return false;
        }
        this.loaApplicantNameSnapshot = applicantName != null ? applicantName : "";
        this.loaCompanyNameSnapshot = companyName;
        this.loaUenSnapshot = uen;
        this.loaDesignationSnapshot = designation;
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
}

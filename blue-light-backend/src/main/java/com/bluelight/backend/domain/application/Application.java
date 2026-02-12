package com.bluelight.backend.domain.application;

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
 * 라이선스 신청 내역 Entity
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
     * 플랫폼 서비스 수수료 (생성 시점 스냅샷)
     */
    @Column(name = "service_fee", precision = 10, scale = 2)
    private BigDecimal serviceFee;

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

    /**
     * 만료 알림 발송 시각 (중복 알림 방지)
     */
    @Column(name = "expiry_notified_at")
    private LocalDateTime expiryNotifiedAt;

    @Builder
    public Application(User user, String address, String postalCode, String buildingType,
                       Integer selectedKva, BigDecimal quoteAmount, BigDecimal serviceFee,
                       String spAccountNo, SldOption sldOption,
                       ApplicationType applicationType, Application originalApplication,
                       String existingLicenceNo, String renewalReferenceNo,
                       LocalDate existingExpiryDate, Integer renewalPeriodMonths,
                       BigDecimal emaFee) {
        this.user = user;
        this.address = address;
        this.postalCode = postalCode;
        this.buildingType = buildingType;
        this.selectedKva = selectedKva;
        this.quoteAmount = quoteAmount;
        this.serviceFee = serviceFee;
        this.spAccountNo = spAccountNo;
        this.sldOption = sldOption != null ? sldOption : SldOption.SELF_UPLOAD;
        this.applicationType = applicationType != null ? applicationType : ApplicationType.NEW;
        this.originalApplication = originalApplication;
        this.existingLicenceNo = existingLicenceNo;
        this.renewalReferenceNo = renewalReferenceNo;
        this.existingExpiryDate = existingExpiryDate;
        this.renewalPeriodMonths = renewalPeriodMonths;
        this.emaFee = emaFee;
        this.status = ApplicationStatus.PENDING_REVIEW;
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
     * 신청 내용 수정 (보완 시)
     */
    public void updateDetails(String address, String postalCode, String buildingType,
                              Integer selectedKva, BigDecimal quoteAmount, BigDecimal serviceFee) {
        this.address = address;
        this.postalCode = postalCode;
        this.buildingType = buildingType;
        this.selectedKva = selectedKva;
        this.quoteAmount = quoteAmount;
        this.serviceFee = serviceFee;
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
}

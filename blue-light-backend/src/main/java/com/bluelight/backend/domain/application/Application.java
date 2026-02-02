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
    private ApplicationStatus status = ApplicationStatus.PENDING_PAYMENT;

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

    @Builder
    public Application(User user, String address, String postalCode, String buildingType,
                       Integer selectedKva, BigDecimal quoteAmount) {
        this.user = user;
        this.address = address;
        this.postalCode = postalCode;
        this.buildingType = buildingType;
        this.selectedKva = selectedKva;
        this.quoteAmount = quoteAmount;
        this.status = ApplicationStatus.PENDING_PAYMENT;
    }

    /**
     * 상태 변경
     */
    public void changeStatus(ApplicationStatus status) {
        this.status = status;
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
}

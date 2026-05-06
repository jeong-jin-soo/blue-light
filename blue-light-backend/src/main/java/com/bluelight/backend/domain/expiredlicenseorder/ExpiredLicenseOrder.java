package com.bluelight.backend.domain.expiredlicenseorder;

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
import java.time.LocalDateTime;

/**
 * Expired License 주문 Entity
 * <p>만료된 라이선스에 대해 재발급/갱신을 요청하는 주문. LEW Service 와 동일한 생애주기를 따른다.
 * <p>참고 자료는 단일 sketch 가 아니라 임의 종류의 문서 복수(최대 10개, 파일당 20MB) 로 업로드한다.
 * 업로드된 문서는 {@code files} 테이블에서 {@code expired_license_order_seq} FK + fileType 으로 조회.
 */
@Entity
@Table(name = "expired_license_orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLDelete(sql = "UPDATE expired_license_orders SET deleted_at = NOW() WHERE expired_license_order_seq = ?")
@SQLRestriction("deleted_at IS NULL")
public class ExpiredLicenseOrder extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "expired_license_order_seq")
    private Long expiredLicenseOrderSeq;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_seq", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_manager_seq")
    private User assignedManager;

    @Column(name = "address", length = 255)
    private String address;

    @Column(name = "postal_code", length = 10)
    private String postalCode;

    @Column(name = "building_type", length = 50)
    private String buildingType;

    @Column(name = "selected_kva")
    private Integer selectedKva;

    @Column(name = "applicant_note", columnDefinition = "TEXT")
    private String applicantNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ExpiredLicenseOrderStatus status = ExpiredLicenseOrderStatus.PENDING_QUOTE;

    @Column(name = "quote_amount", precision = 10, scale = 2)
    private BigDecimal quoteAmount;

    @Column(name = "quote_note", columnDefinition = "TEXT")
    private String quoteNote;

    @Column(name = "manager_note", columnDefinition = "TEXT")
    private String managerNote;

    @Column(name = "revisit_comment", columnDefinition = "TEXT")
    private String revisitComment;

    @Column(name = "visit_scheduled_at")
    private LocalDateTime visitScheduledAt;

    @Column(name = "visit_schedule_note", length = 2000, columnDefinition = "TEXT")
    private String visitScheduleNote;

    @Column(name = "check_in_at")
    private LocalDateTime checkInAt;

    @Column(name = "check_out_at")
    private LocalDateTime checkOutAt;

    @Column(name = "visit_report_file_seq")
    private Long visitReportFileSeq;

    @Builder
    public ExpiredLicenseOrder(User user, String address, String postalCode,
                               String buildingType, Integer selectedKva, String applicantNote,
                               LocalDateTime visitScheduledAt, String visitScheduleNote) {
        this.user = user;
        this.address = address;
        this.postalCode = postalCode;
        this.buildingType = buildingType;
        this.selectedKva = selectedKva;
        this.applicantNote = applicantNote;
        this.visitScheduledAt = visitScheduledAt;
        this.visitScheduleNote = visitScheduleNote;
        this.status = ExpiredLicenseOrderStatus.PENDING_QUOTE;
    }

    public void proposeQuote(BigDecimal amount, String note) {
        if (this.status != ExpiredLicenseOrderStatus.PENDING_QUOTE) {
            throw new IllegalStateException("견적 제안은 PENDING_QUOTE 상태에서만 가능합니다. 현재: " + this.status);
        }
        this.quoteAmount = amount;
        this.quoteNote = note;
        this.status = ExpiredLicenseOrderStatus.QUOTE_PROPOSED;
    }

    public void acceptQuote() {
        if (this.status != ExpiredLicenseOrderStatus.QUOTE_PROPOSED) {
            throw new IllegalStateException("견적 수락은 QUOTE_PROPOSED 상태에서만 가능합니다. 현재: " + this.status);
        }
        this.status = ExpiredLicenseOrderStatus.PENDING_PAYMENT;
    }

    public void rejectQuote() {
        if (this.status != ExpiredLicenseOrderStatus.QUOTE_PROPOSED) {
            throw new IllegalStateException("견적 거절은 QUOTE_PROPOSED 상태에서만 가능합니다. 현재: " + this.status);
        }
        this.status = ExpiredLicenseOrderStatus.QUOTE_REJECTED;
    }

    public void markAsPaid() {
        if (this.status != ExpiredLicenseOrderStatus.PENDING_PAYMENT) {
            throw new IllegalStateException("결제 확인은 PENDING_PAYMENT 상태에서만 가능합니다. 현재: " + this.status);
        }
        this.status = ExpiredLicenseOrderStatus.PAID;
    }

    public void startVisit() {
        if (this.status != ExpiredLicenseOrderStatus.PAID) {
            throw new IllegalStateException("방문 시작은 PAID 상태에서만 가능합니다. 현재: " + this.status);
        }
        this.status = ExpiredLicenseOrderStatus.VISIT_SCHEDULED;
    }

    public void checkIn() {
        if (this.status != ExpiredLicenseOrderStatus.VISIT_SCHEDULED) {
            throw new IllegalStateException(
                    "Check-in is only allowed in VISIT_SCHEDULED state. Current: " + this.status);
        }
        this.checkInAt = LocalDateTime.now();
    }

    public void checkOut(Long visitReportFileSeq, String managerNote) {
        if (this.status != ExpiredLicenseOrderStatus.VISIT_SCHEDULED) {
            throw new IllegalStateException(
                    "Check-out is only allowed in VISIT_SCHEDULED state. Current: " + this.status);
        }
        if (this.checkInAt == null) {
            throw new IllegalStateException("Check-out requires prior check-in");
        }
        if (visitReportFileSeq == null) {
            throw new IllegalArgumentException("visitReportFileSeq is required");
        }
        this.checkOutAt = LocalDateTime.now();
        this.visitReportFileSeq = visitReportFileSeq;
        this.managerNote = managerNote;
        this.status = ExpiredLicenseOrderStatus.VISIT_COMPLETED;
    }

    public void requestRevisit(String comment) {
        if (this.status != ExpiredLicenseOrderStatus.VISIT_COMPLETED) {
            throw new IllegalStateException(
                    "Revisit request is only allowed in VISIT_COMPLETED state. Current: " + this.status);
        }
        this.revisitComment = comment;
        this.status = ExpiredLicenseOrderStatus.REVISIT_REQUESTED;
    }

    public void complete() {
        if (this.status != ExpiredLicenseOrderStatus.VISIT_COMPLETED) {
            throw new IllegalStateException(
                    "Completion is only allowed in VISIT_COMPLETED state. Current: " + this.status);
        }
        this.status = ExpiredLicenseOrderStatus.COMPLETED;
    }

    public void assignManager(User manager) {
        this.assignedManager = manager;
    }

    public void unassignManager() {
        this.assignedManager = null;
    }

    public void updateApplicantNote(String applicantNote) {
        if (this.status != ExpiredLicenseOrderStatus.PENDING_QUOTE) {
            throw new IllegalStateException("수정은 PENDING_QUOTE 상태에서만 가능합니다. 현재: " + this.status);
        }
        this.applicantNote = applicantNote;
    }

    public void ensureVisitScheduled() {
        if (this.status == ExpiredLicenseOrderStatus.PAID) {
            this.status = ExpiredLicenseOrderStatus.VISIT_SCHEDULED;
        }
    }

    public void scheduleVisit(LocalDateTime when, String note) {
        if (when == null) {
            throw new IllegalArgumentException("visitScheduledAt must not be null");
        }
        if (this.status != ExpiredLicenseOrderStatus.PAID
                && this.status != ExpiredLicenseOrderStatus.VISIT_SCHEDULED
                && this.status != ExpiredLicenseOrderStatus.REVISIT_REQUESTED) {
            throw new IllegalStateException(
                    "Visit can only be scheduled in PAID, VISIT_SCHEDULED, or REVISIT_REQUESTED state. Current: "
                            + this.status);
        }
        this.visitScheduledAt = when;
        this.visitScheduleNote = note;
    }

    public boolean isOnSite() {
        return this.status == ExpiredLicenseOrderStatus.VISIT_SCHEDULED && this.checkInAt != null;
    }
}

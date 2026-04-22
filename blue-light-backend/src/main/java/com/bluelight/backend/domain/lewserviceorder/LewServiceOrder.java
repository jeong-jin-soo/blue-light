package com.bluelight.backend.domain.lewserviceorder;

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
 * Request for LEW Service 주문 Entity
 * - 라이센스 신청 없이 SLD 도면만 요청하는 경우
 * - SLD_MANAGER가 견적 제안 → 신청자 수락 → 결제 → SLD 생성 → 완료
 */
@Entity
@Table(name = "lew_service_orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLDelete(sql = "UPDATE lew_service_orders SET deleted_at = NOW() WHERE lew_service_order_seq = ?")
@SQLRestriction("deleted_at IS NULL")
public class LewServiceOrder extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "lew_service_order_seq")
    private Long lewServiceOrderSeq;

    /**
     * 신청자 (FK)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_seq", nullable = false)
    private User user;

    /**
     * 담당 LewService Manager (FK, nullable)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_manager_seq")
    private User assignedManager;

    /**
     * 설치 주소 (선택)
     */
    @Column(name = "address", length = 255)
    private String address;

    /**
     * 우편번호 (선택)
     */
    @Column(name = "postal_code", length = 10)
    private String postalCode;

    /**
     * 건물 유형 (선택)
     */
    @Column(name = "building_type", length = 50)
    private String buildingType;

    /**
     * 용량 kVA (선택 — 없으면 SLD_MANAGER가 판단)
     */
    @Column(name = "selected_kva")
    private Integer selectedKva;

    /**
     * 신청자 요구사항 메모
     */
    @Column(name = "applicant_note", columnDefinition = "TEXT")
    private String applicantNote;

    /**
     * 참조 스케치 파일 (FK → files)
     */
    @Column(name = "sketch_file_seq")
    private Long sketchFileSeq;

    /**
     * 주문 상태
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private LewServiceOrderStatus status = LewServiceOrderStatus.PENDING_QUOTE;

    /**
     * 견적 금액 (SLD_MANAGER가 제안)
     */
    @Column(name = "quote_amount", precision = 10, scale = 2)
    private BigDecimal quoteAmount;

    /**
     * 견적 메모 (SLD_MANAGER)
     */
    @Column(name = "quote_note", columnDefinition = "TEXT")
    private String quoteNote;

    /**
     * 작업 완료 메모 (SLD_MANAGER)
     */
    @Column(name = "manager_note", columnDefinition = "TEXT")
    private String managerNote;

    /**
     * 완성된 Request for LEW Service 파일 (FK → files)
     */
    @Column(name = "uploaded_file_seq")
    private Long uploadedFileSeq;

    /**
     * 수정 요청 사유 (신청자)
     */
    @Column(name = "revision_comment", columnDefinition = "TEXT")
    private String revisionComment;

    /**
     * 합의된 방문 예정 일시 (LEW Service 방문형 리스키닝 PR 2)
     * null 이면 아직 일정 미확정
     */
    @Column(name = "visit_scheduled_at")
    private LocalDateTime visitScheduledAt;

    /**
     * 방문 일정 관련 메모 (예: "현관 벨 고장, 전화 주세요")
     */
    @Column(name = "visit_schedule_note", length = 2000, columnDefinition = "TEXT")
    private String visitScheduleNote;

    @Builder
    public LewServiceOrder(User user, String address, String postalCode,
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
        this.status = LewServiceOrderStatus.PENDING_QUOTE;
    }

    // ── 상태 전환 메서드 ────────────────────────────

    /**
     * 견적 제안 (SLD_MANAGER)
     */
    public void proposeQuote(BigDecimal amount, String note) {
        if (this.status != LewServiceOrderStatus.PENDING_QUOTE) {
            throw new IllegalStateException("견적 제안은 PENDING_QUOTE 상태에서만 가능합니다. 현재: " + this.status);
        }
        this.quoteAmount = amount;
        this.quoteNote = note;
        this.status = LewServiceOrderStatus.QUOTE_PROPOSED;
    }

    /**
     * 견적 수락 (신청자)
     */
    public void acceptQuote() {
        if (this.status != LewServiceOrderStatus.QUOTE_PROPOSED) {
            throw new IllegalStateException("견적 수락은 QUOTE_PROPOSED 상태에서만 가능합니다. 현재: " + this.status);
        }
        this.status = LewServiceOrderStatus.PENDING_PAYMENT;
    }

    /**
     * 견적 거절 (신청자)
     */
    public void rejectQuote() {
        if (this.status != LewServiceOrderStatus.QUOTE_PROPOSED) {
            throw new IllegalStateException("견적 거절은 QUOTE_PROPOSED 상태에서만 가능합니다. 현재: " + this.status);
        }
        this.status = LewServiceOrderStatus.QUOTE_REJECTED;
    }

    /**
     * 결제 완료
     */
    public void markAsPaid() {
        if (this.status != LewServiceOrderStatus.PENDING_PAYMENT) {
            throw new IllegalStateException("결제 확인은 PENDING_PAYMENT 상태에서만 가능합니다. 현재: " + this.status);
        }
        this.status = LewServiceOrderStatus.PAID;
    }

    /**
     * SLD 작업 시작
     */
    public void startWork() {
        if (this.status != LewServiceOrderStatus.PAID) {
            throw new IllegalStateException("작업 시작은 PAID 상태에서만 가능합니다. 현재: " + this.status);
        }
        this.status = LewServiceOrderStatus.IN_PROGRESS;
    }

    /**
     * Request for LEW Service 업로드 완료 (SLD_MANAGER)
     */
    public void uploadSld(Long fileSeq, String managerNote) {
        if (this.status != LewServiceOrderStatus.IN_PROGRESS
                && this.status != LewServiceOrderStatus.REVISION_REQUESTED
                && this.status != LewServiceOrderStatus.SLD_UPLOADED) {
            throw new IllegalStateException("Request for LEW Service 업로드는 IN_PROGRESS, REVISION_REQUESTED 또는 SLD_UPLOADED 상태에서만 가능합니다. 현재: " + this.status);
        }
        this.uploadedFileSeq = fileSeq;
        this.managerNote = managerNote;
        this.status = LewServiceOrderStatus.SLD_UPLOADED;
    }

    /**
     * 수정 요청 (신청자)
     */
    public void requestRevision(String comment) {
        if (this.status != LewServiceOrderStatus.SLD_UPLOADED) {
            throw new IllegalStateException("수정 요청은 SLD_UPLOADED 상태에서만 가능합니다. 현재: " + this.status);
        }
        this.revisionComment = comment;
        this.status = LewServiceOrderStatus.REVISION_REQUESTED;
    }

    /**
     * 완료 확인 (신청자)
     */
    public void complete() {
        if (this.status != LewServiceOrderStatus.SLD_UPLOADED) {
            throw new IllegalStateException("완료는 SLD_UPLOADED 상태에서만 가능합니다. 현재: " + this.status);
        }
        this.status = LewServiceOrderStatus.COMPLETED;
    }

    /**
     * LewService Manager 배정
     */
    public void assignManager(User manager) {
        this.assignedManager = manager;
    }

    /**
     * LewService Manager 배정 해제
     */
    public void unassignManager() {
        this.assignedManager = null;
    }

    /**
     * 신청자가 메모와 스케치 파일 수정 (PENDING_QUOTE 상태에서만)
     */
    public void updateDetails(String applicantNote, Long sketchFileSeq) {
        if (this.status != LewServiceOrderStatus.PENDING_QUOTE) {
            throw new IllegalStateException("수정은 PENDING_QUOTE 상태에서만 가능합니다. 현재: " + this.status);
        }
        this.applicantNote = applicantNote;
        this.sketchFileSeq = sketchFileSeq;
    }

    /**
     * AI 생성 시작 (PAID → IN_PROGRESS 자동 전환)
     */
    public void ensureInProgress() {
        if (this.status == LewServiceOrderStatus.PAID) {
            this.status = LewServiceOrderStatus.IN_PROGRESS;
        }
    }

    /**
     * 방문 일정 예약 (LEW Service 방문형 리스키닝 PR 2)
     * <p>
     * 상태 전이 없음 — 일정 데이터만 덮어쓴다. 재예약(Reschedule) 시에도 동일 메서드 호출.
     * PAID / IN_PROGRESS / REVISION_REQUESTED 상태에서만 호출 가능.
     *
     * @param when 합의된 방문 예정 일시 (null 불가)
     * @param note 방문 관련 메모 (nullable)
     */
    public void scheduleVisit(LocalDateTime when, String note) {
        if (when == null) {
            throw new IllegalArgumentException("visitScheduledAt must not be null");
        }
        if (this.status != LewServiceOrderStatus.PAID
                && this.status != LewServiceOrderStatus.IN_PROGRESS
                && this.status != LewServiceOrderStatus.REVISION_REQUESTED) {
            throw new IllegalStateException(
                    "Visit can only be scheduled in PAID, IN_PROGRESS, or REVISION_REQUESTED state. Current: "
                            + this.status);
        }
        this.visitScheduledAt = when;
        this.visitScheduleNote = note;
    }
}

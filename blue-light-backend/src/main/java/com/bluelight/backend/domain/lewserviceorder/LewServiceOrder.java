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
 * - LEW (licensed electrical worker) 가 현장을 방문해 전기 공사/검사를 수행하는 서비스 주문.
 * - 견적 → 결제 → 방문 일정 → 체크인/체크아웃 → 보고서 제출 → 신청자 확인 → 완료.
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
     * 완성된 Request for LEW Service 파일 (FK → files).
     * <p>Legacy — PR 3 에서 {@link #visitReportFileSeq} 로 대체. 하위호환용으로 유지
     * (기존 {@code /sld-uploaded} 어댑터가 여전히 사용).
     */
    @Column(name = "uploaded_file_seq")
    private Long uploadedFileSeq;

    /**
     * 재방문 요청 사유 (신청자). PR 3 에서 {@code revision_comment} 에서 rename.
     */
    @Column(name = "revisit_comment", columnDefinition = "TEXT")
    private String revisitComment;

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

    /**
     * LEW 체크인 시각 (현장 도착, PR 3).
     * <p>{@code status=VISIT_SCHEDULED && checkInAt IS NOT NULL} 을 "ON_SITE"로 해석.
     */
    @Column(name = "check_in_at")
    private LocalDateTime checkInAt;

    /**
     * LEW 체크아웃 시각 (현장 작업 종료, PR 3).
     */
    @Column(name = "check_out_at")
    private LocalDateTime checkOutAt;

    /**
     * 방문 보고서 파일 (FK → files). PR 3 — {@code uploadedFileSeq} 를 대체.
     */
    @Column(name = "visit_report_file_seq")
    private Long visitReportFileSeq;

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
     * 방문 작업 시작 — PAID → VISIT_SCHEDULED.
     * (과거 {@code startWork()} 의 계승. 이름을 바꾸지 않아도 안전하지만
     * "Visit Scheduled" 시맨틱으로 정렬하기 위해 리네임.)
     */
    public void startVisit() {
        if (this.status != LewServiceOrderStatus.PAID) {
            throw new IllegalStateException("방문 시작은 PAID 상태에서만 가능합니다. 현재: " + this.status);
        }
        this.status = LewServiceOrderStatus.VISIT_SCHEDULED;
    }

    /**
     * LEW 체크인 (현장 도착, PR 3).
     * <p>{@code VISIT_SCHEDULED} 에서만 호출 가능. 상태는 바뀌지 않고 {@link #checkInAt} 만 기록.
     * UI 에서는 {@code status=VISIT_SCHEDULED && checkInAt != null} 를 "ON_SITE"로 해석.
     */
    public void checkIn() {
        if (this.status != LewServiceOrderStatus.VISIT_SCHEDULED) {
            throw new IllegalStateException(
                    "Check-in is only allowed in VISIT_SCHEDULED state. Current: " + this.status);
        }
        this.checkInAt = LocalDateTime.now();
    }

    /**
     * LEW 체크아웃 + 방문 보고서 제출 (PR 3).
     * <p>{@code VISIT_SCHEDULED} 에서 {@link #checkInAt} 가 세팅되어 있어야 호출 가능.
     * 상태는 {@link LewServiceOrderStatus#VISIT_COMPLETED} 로 전이.
     *
     * @param visitReportFileSeq 방문 보고서 파일 seq (필수)
     * @param managerNote        LEW 메모 (nullable)
     */
    public void checkOut(Long visitReportFileSeq, String managerNote) {
        if (this.status != LewServiceOrderStatus.VISIT_SCHEDULED) {
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
        // 하위호환 — legacy 소비자를 위해 uploadedFileSeq 에도 미러링
        this.uploadedFileSeq = visitReportFileSeq;
        this.managerNote = managerNote;
        this.status = LewServiceOrderStatus.VISIT_COMPLETED;
    }

    /**
     * 하위호환 어댑터 — 구 {@code uploadSld()} 호출 경로 유지.
     * <p>기존 {@code /sld-uploaded} 엔드포인트는 PR 3 에서 deprecate 되었으나 1 개월간 유지.
     * 방문 일정/체크인이 아직 없는 경우 자동 보강하여 VISIT_COMPLETED 로 전이시킨다.
     * <ul>
     *   <li>PAID → VISIT_SCHEDULED 로 전이 후 진행</li>
     *   <li>VISIT_SCHEDULED + checkInAt==null → 즉시 checkIn() 수행</li>
     *   <li>REVISIT_REQUESTED → 방문 재시작으로 간주 (VISIT_SCHEDULED + checkIn + checkOut)</li>
     *   <li>VISIT_COMPLETED → 보고서 재제출 (visitReportFileSeq 만 갱신)</li>
     * </ul>
     */
    public void legacyUploadDeliverable(Long fileSeq, String managerNote) {
        if (fileSeq == null) {
            throw new IllegalArgumentException("fileSeq is required");
        }
        LocalDateTime now = LocalDateTime.now();

        switch (this.status) {
            case PAID -> {
                this.status = LewServiceOrderStatus.VISIT_SCHEDULED;
                this.checkInAt = now;
                this.checkOutAt = now;
                this.status = LewServiceOrderStatus.VISIT_COMPLETED;
            }
            case VISIT_SCHEDULED -> {
                if (this.checkInAt == null) this.checkInAt = now;
                this.checkOutAt = now;
                this.status = LewServiceOrderStatus.VISIT_COMPLETED;
            }
            case REVISIT_REQUESTED -> {
                this.status = LewServiceOrderStatus.VISIT_SCHEDULED;
                this.checkInAt = now;
                this.checkOutAt = now;
                this.status = LewServiceOrderStatus.VISIT_COMPLETED;
            }
            case VISIT_COMPLETED -> {
                // 보고서 재제출 — 체크아웃 시각만 갱신
                this.checkOutAt = now;
            }
            default -> throw new IllegalStateException(
                    "Legacy upload is not allowed in state: " + this.status);
        }

        this.visitReportFileSeq = fileSeq;
        this.uploadedFileSeq = fileSeq;
        this.managerNote = managerNote;
    }

    /**
     * 재방문 요청 (신청자). PR 3 — 기존 {@code requestRevision} rename.
     */
    public void requestRevisit(String comment) {
        if (this.status != LewServiceOrderStatus.VISIT_COMPLETED) {
            throw new IllegalStateException(
                    "Revisit request is only allowed in VISIT_COMPLETED state. Current: " + this.status);
        }
        this.revisitComment = comment;
        this.status = LewServiceOrderStatus.REVISIT_REQUESTED;
    }

    /**
     * 완료 확인 (신청자) — PR 3: VISIT_COMPLETED 에서만 호출.
     */
    public void complete() {
        if (this.status != LewServiceOrderStatus.VISIT_COMPLETED) {
            throw new IllegalStateException(
                    "Completion is only allowed in VISIT_COMPLETED state. Current: " + this.status);
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
     * PAID → VISIT_SCHEDULED 자동 전환 (하위호환 어댑터 등에서 사용).
     */
    public void ensureVisitScheduled() {
        if (this.status == LewServiceOrderStatus.PAID) {
            this.status = LewServiceOrderStatus.VISIT_SCHEDULED;
        }
    }

    /**
     * 방문 일정 예약 (LEW Service 방문형 리스키닝 PR 2)
     * <p>
     * 상태 전이 없음 — 일정 데이터만 덮어쓴다. 재예약(Reschedule) 시에도 동일 메서드 호출.
     * PAID / VISIT_SCHEDULED / REVISIT_REQUESTED 상태에서만 호출 가능.
     *
     * @param when 합의된 방문 예정 일시 (null 불가)
     * @param note 방문 관련 메모 (nullable)
     */
    public void scheduleVisit(LocalDateTime when, String note) {
        if (when == null) {
            throw new IllegalArgumentException("visitScheduledAt must not be null");
        }
        if (this.status != LewServiceOrderStatus.PAID
                && this.status != LewServiceOrderStatus.VISIT_SCHEDULED
                && this.status != LewServiceOrderStatus.REVISIT_REQUESTED) {
            throw new IllegalStateException(
                    "Visit can only be scheduled in PAID, VISIT_SCHEDULED, or REVISIT_REQUESTED state. Current: "
                            + this.status);
        }
        this.visitScheduledAt = when;
        this.visitScheduleNote = note;
    }

    /**
     * ON_SITE 파생 상태 확인 — status=VISIT_SCHEDULED && checkInAt != null.
     */
    public boolean isOnSite() {
        return this.status == LewServiceOrderStatus.VISIT_SCHEDULED && this.checkInAt != null;
    }
}

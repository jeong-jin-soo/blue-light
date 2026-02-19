package com.bluelight.backend.domain.breach;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 데이터 유출 통보 Entity (PDPA 준수)
 * - PDPA에 따라 3일 이내 PDPC 통보 의무
 */
@Entity
@Table(name = "data_breach_notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DataBreachNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "breach_seq")
    private Long breachSeq;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    private BreachSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private BreachStatus status;

    @Column(name = "affected_count")
    private Integer affectedCount;

    @Column(name = "data_types_affected", length = 500)
    private String dataTypesAffected;

    @Column(name = "containment_actions", columnDefinition = "TEXT")
    private String containmentActions;

    @Column(name = "pdpc_notified_at")
    private LocalDateTime pdpcNotifiedAt;

    @Column(name = "pdpc_reference_no", length = 100)
    private String pdpcReferenceNo;

    @Column(name = "users_notified_at")
    private LocalDateTime usersNotifiedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "reported_by")
    private Long reportedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Builder
    public DataBreachNotification(String title, String description, BreachSeverity severity,
                                   Integer affectedCount, String dataTypesAffected,
                                   String containmentActions, Long reportedBy) {
        this.title = title;
        this.description = description;
        this.severity = severity != null ? severity : BreachSeverity.HIGH;
        this.status = BreachStatus.DETECTED;
        this.affectedCount = affectedCount != null ? affectedCount : 0;
        this.dataTypesAffected = dataTypesAffected;
        this.containmentActions = containmentActions;
        this.reportedBy = reportedBy;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * PDPC 통보 완료 처리
     */
    public void markPdpcNotified(String pdpcReferenceNo) {
        this.pdpcNotifiedAt = LocalDateTime.now();
        this.pdpcReferenceNo = pdpcReferenceNo;
        this.status = BreachStatus.PDPC_NOTIFIED;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 영향 받은 사용자 통보 완료 처리
     */
    public void markUsersNotified() {
        this.usersNotifiedAt = LocalDateTime.now();
        this.status = BreachStatus.USERS_NOTIFIED;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 상태 업데이트
     */
    public void updateStatus(BreachStatus status) {
        this.status = status;
        if (status == BreachStatus.RESOLVED) {
            this.resolvedAt = LocalDateTime.now();
        }
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 차단 조치 업데이트
     */
    public void updateContainmentActions(String containmentActions) {
        this.containmentActions = containmentActions;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * PDPC 통보 기한 확인 (3일 이내)
     */
    public boolean isPdpcNotificationOverdue() {
        if (pdpcNotifiedAt != null) return false;
        return createdAt.plusDays(3).isBefore(LocalDateTime.now());
    }
}

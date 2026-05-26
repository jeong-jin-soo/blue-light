package com.bluelight.backend.domain.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 알림 템플릿 변경 이력 — append-only 감사 로그 + 롤백 진입점.
 *
 * <p><b>BaseEntity 미상속</b> — 감사 무결성을 위해 append-only 정책. {@link NotificationOutbox}와 동일한
 * 사유로 soft delete 도 적용하지 않는다.</p>
 *
 * <p>{@link #beforeSnapshotJson} / {@link #afterSnapshotJson} 은 전체 row 스냅샷(롤백용),
 * {@link #diffJson} 은 변경 필드만의 압축 diff(UI 표시용).</p>
 *
 * <p>스펙: {@code doc/Project Analysis/notification-template-manager-spec.md} §5.3, §9.3.</p>
 */
@Entity
@Table(
        name = "notification_template_history",
        indexes = {
                @Index(name = "idx_history_template", columnList = "template_seq, changed_at DESC"),
                @Index(name = "idx_history_actor", columnList = "actor_user_seq, changed_at DESC")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationTemplateHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "history_seq")
    private Long historySeq;

    @Column(name = "template_seq", nullable = false)
    private Long templateSeq;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 20)
    private TemplateChangeType changeType;

    /** 변경 필드만의 압축 diff — {@code {before:{...changed fields...}, after:{...}}}. */
    @Column(name = "diff_json", nullable = false, columnDefinition = "TEXT")
    private String diffJson;

    /** 전체 row 스냅샷 — 롤백 시 사용. CREATE 시점에는 빈 객체 {@code {}}. */
    @Column(name = "before_snapshot_json", nullable = false, columnDefinition = "TEXT")
    private String beforeSnapshotJson;

    @Column(name = "after_snapshot_json", nullable = false, columnDefinition = "TEXT")
    private String afterSnapshotJson;

    /** SECURITY/PAYMENT/MARKETING (D-6) 카테고리는 서비스 레이어에서 필수 검증. */
    @Column(name = "change_reason", length = 500)
    private String changeReason;

    @Column(name = "actor_user_seq", nullable = false)
    private Long actorUserSeq;

    /** 감사 — 실제 요청 IP (IPv4/IPv6 둘 다 수용). */
    @Column(name = "actor_ip", length = 45)
    private String actorIp;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;

    @Builder
    public NotificationTemplateHistory(Long templateSeq,
                                       TemplateChangeType changeType,
                                       String diffJson,
                                       String beforeSnapshotJson,
                                       String afterSnapshotJson,
                                       String changeReason,
                                       Long actorUserSeq,
                                       String actorIp) {
        this.templateSeq = templateSeq;
        this.changeType = changeType;
        this.diffJson = diffJson;
        this.beforeSnapshotJson = beforeSnapshotJson;
        this.afterSnapshotJson = afterSnapshotJson;
        this.changeReason = changeReason;
        this.actorUserSeq = actorUserSeq;
        this.actorIp = actorIp;
        this.changedAt = LocalDateTime.now();
    }
}

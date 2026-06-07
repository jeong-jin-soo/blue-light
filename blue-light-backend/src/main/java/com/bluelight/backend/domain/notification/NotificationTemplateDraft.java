package com.bluelight.backend.domain.notification;

import com.bluelight.backend.domain.common.BaseEntity;
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
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * 알림 템플릿 Draft — D-1 결정에 따른 2-step publish 워크플로의 staging row.
 *
 * <p><b>흐름</b>:
 * <ol>
 *   <li>NM 이 편집·저장 → {@code status=PENDING}, {@link #submittedBy}/{@link #submittedAt} 기록</li>
 *   <li>SYSTEM_ADMIN approve → 본 테이블({@link NotificationTemplate})에 commit + {@link NotificationTemplateHistory} insert</li>
 *   <li>SYSTEM_ADMIN reject → {@code status=REJECTED}, {@link #reviewNote} 필수</li>
 *   <li>작성자 withdraw → {@code status=WITHDRAWN}</li>
 * </ol>
 *
 * <p>{@link #templateSeq}가 null 이면 신규 템플릿(아직 본 테이블에 row 없음).
 * non-null 이면 기존 row 에 대한 수정 draft.</p>
 *
 * <p>스펙: {@code doc/Project Analysis/notification-template-manager-spec.md} §5.2, §9.</p>
 */
@Entity
@Table(
        name = "notification_template_drafts",
        indexes = {
                @Index(name = "idx_draft_status", columnList = "status, submitted_at"),
                @Index(name = "idx_draft_template", columnList = "template_seq")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLDelete(sql = "UPDATE notification_template_drafts SET deleted_at = NOW() WHERE draft_seq = ?")
@SQLRestriction("deleted_at IS NULL")
public class NotificationTemplateDraft extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "draft_seq")
    private Long draftSeq;

    /** null = 신규 템플릿 draft, non-null = 기존 row 수정 draft. */
    @Column(name = "template_seq")
    private Long templateSeq;

    @Column(name = "template_code", nullable = false, length = 80)
    private String templateCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private NotificationChannel channel;

    @Column(name = "locale", nullable = false, length = 10)
    private String locale;

    @Column(name = "subject", length = 200)
    private String subject;

    @Column(name = "body_text", nullable = false, columnDefinition = "TEXT")
    private String bodyText;

    @Column(name = "variables_json", columnDefinition = "TEXT")
    private String variablesJson;

    @Column(name = "provider_template_name", length = 120)
    private String providerTemplateName;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 30)
    private NotificationCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 20)
    private NotificationSeverity severity;

    /** comma-separated, 예: {@code APPLICANT,LEW}. */
    @Column(name = "recipient_roles", length = 200)
    private String recipientRoles;

    @Column(name = "submitted_by", nullable = false)
    private Long submittedBy;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    /** "오탈자 수정", "법무팀 요청 반영" 등. */
    @Column(name = "submission_note", length = 500)
    private String submissionNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TemplateDraftStatus status;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_note", length = 500)
    private String reviewNote;

    @Builder
    public NotificationTemplateDraft(Long templateSeq,
                                     String templateCode,
                                     NotificationChannel channel,
                                     String locale,
                                     String subject,
                                     String bodyText,
                                     String variablesJson,
                                     String providerTemplateName,
                                     NotificationCategory category,
                                     NotificationSeverity severity,
                                     String recipientRoles,
                                     Long submittedBy,
                                     String submissionNote) {
        this.templateSeq = templateSeq;
        this.templateCode = templateCode;
        this.channel = channel;
        this.locale = locale;
        this.subject = subject;
        this.bodyText = bodyText;
        this.variablesJson = variablesJson;
        this.providerTemplateName = providerTemplateName;
        this.category = category;
        this.severity = severity;
        this.recipientRoles = recipientRoles;
        this.submittedBy = submittedBy;
        this.submittedAt = LocalDateTime.now();
        this.submissionNote = submissionNote;
        this.status = TemplateDraftStatus.PENDING;
    }

    /** NM 본인 편집 — 본문/제목/메타 수정. PENDING 상태에서만 허용. */
    public void edit(String subject,
                     String bodyText,
                     String variablesJson,
                     String providerTemplateName,
                     NotificationCategory category,
                     NotificationSeverity severity,
                     String recipientRoles,
                     String submissionNote) {
        ensurePending();
        this.subject = subject;
        this.bodyText = bodyText;
        this.variablesJson = variablesJson;
        this.providerTemplateName = providerTemplateName;
        this.category = category;
        this.severity = severity;
        this.recipientRoles = recipientRoles;
        this.submissionNote = submissionNote;
    }

    public void approve(Long reviewerSeq, String reviewNote) {
        ensurePending();
        this.status = TemplateDraftStatus.APPROVED;
        this.reviewedBy = reviewerSeq;
        this.reviewedAt = LocalDateTime.now();
        this.reviewNote = reviewNote;
    }

    public void reject(Long reviewerSeq, String reviewNote) {
        ensurePending();
        if (reviewNote == null || reviewNote.isBlank()) {
            throw new IllegalArgumentException("reject 시 reviewNote 는 필수입니다.");
        }
        this.status = TemplateDraftStatus.REJECTED;
        this.reviewedBy = reviewerSeq;
        this.reviewedAt = LocalDateTime.now();
        this.reviewNote = reviewNote;
    }

    public void withdraw() {
        ensurePending();
        this.status = TemplateDraftStatus.WITHDRAWN;
    }

    private void ensurePending() {
        if (this.status != TemplateDraftStatus.PENDING) {
            throw new IllegalStateException("draft 상태가 PENDING 이 아닙니다: " + this.status);
        }
    }
}

package com.bluelight.backend.domain.manualemail;

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

import java.time.LocalDateTime;

/**
 * ADMIN 수동 이메일 발송 row.
 *
 * <p>스펙: {@code doc/Project Analysis/admin-manual-email-spec.md} §4 데이터 모델.</p>
 *
 * <h2>설계 메모</h2>
 * <ul>
 *   <li><b>Soft delete 금지</b> — 감사 무결성 (스펙 §4 + §9.4). {@code @SQLDelete}/{@code @SQLRestriction}
 *       을 적용하지 않고, BaseEntity 의 {@code deletedAt} 컬럼은 보존만 한다 (사용 금지).</li>
 *   <li><b>PR-1 단일 수신자 전용</b> — {@link #recipientUserSeq} 또는 {@link #recipientEmail} 중 하나가
 *       채워진다. {@code MULTI} 는 컨트롤러에서 거부 + JSON 컬럼은 PR-2 에서 추가.</li>
 *   <li><b>발송 트랜잭션 분리</b> — {@code @Transactional} 내에서 row 를 PENDING 으로 저장하고,
 *       {@code TransactionPhase.AFTER_COMMIT} 에서 SMTP 호출 후 {@link #markSent} /
 *       {@link #markFailed} 로 status 만 갱신한다 (실패 격리, 스펙 §8.7).</li>
 *   <li><b>BaseEntity audit</b> — createdAt/updatedAt/createdBy/updatedBy 가 자동 채워진다.
 *       {@code createdBy} 는 발송 ADMIN 이 되며 {@link #senderUserSeq} 와 동일하다 (FK 명시는 별개).</li>
 * </ul>
 */
@Entity
@Table(
        name = "manual_email_dispatches",
        indexes = {
                @Index(name = "idx_manual_email_sender", columnList = "sender_user_seq, dispatched_at DESC"),
                @Index(name = "idx_manual_email_dispatched", columnList = "dispatched_at DESC"),
                @Index(name = "idx_manual_email_status", columnList = "dispatch_status, dispatched_at DESC"),
                @Index(name = "idx_manual_email_application", columnList = "related_application_seq")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ManualEmailDispatch extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "dispatch_seq")
    private Long dispatchSeq;

    /** 발송 ADMIN/SYSTEM_ADMIN userSeq. FK 는 schema.sql 에서 정의. */
    @Column(name = "sender_user_seq", nullable = false)
    private Long senderUserSeq;

    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_type", nullable = false, length = 20)
    private RecipientType recipientType;

    /**
     * 시스템 사용자 단일 수신 시 user_seq. EXTERNAL 일 때는 null.
     * (PR-2 에서 다수 수신을 위한 별도 JSON 컬럼이 추가될 예정.)
     */
    @Column(name = "recipient_user_seq")
    private Long recipientUserSeq;

    /**
     * 실제 발송된 이메일 주소. 시스템 사용자라면 발송 시점의 user.email 을 스냅샷 저장,
     * EXTERNAL 이라면 ADMIN 이 입력한 이메일을 그대로 저장.
     * 사후 사용자 이메일 변경/삭제와 무관하게 발송 이력의 정본이 된다.
     */
    @Column(name = "recipient_email", nullable = false, length = 254)
    private String recipientEmail;

    /** 신청 컨텍스트 연결 (옵션). */
    @Column(name = "related_application_seq")
    private Long relatedApplicationSeq;

    @Column(name = "subject", nullable = false, length = 200)
    private String subject;

    @Column(name = "body_text", nullable = false, columnDefinition = "TEXT")
    private String bodyText;

    @Enumerated(EnumType.STRING)
    @Column(name = "body_format", nullable = false, length = 20)
    private BodyFormat bodyFormat;

    /**
     * 자유 분류 태그 (예: PAYMENT_NOTICE / MAINTENANCE / MISC).
     * PR-1 은 자유 입력 + null 허용. PR-4 에서 system_settings 추천 드롭다운으로 발전.
     */
    @Column(name = "category_tag", length = 50)
    private String categoryTag;

    @Enumerated(EnumType.STRING)
    @Column(name = "dispatch_status", nullable = false, length = 20)
    private DispatchStatus dispatchStatus;

    /** SMTP 발송 성공 수 (단일 발송 PR-1 에서는 0 또는 1). */
    @Column(name = "sent_count", nullable = false)
    private int sentCount;

    /** SMTP 발송 실패 수 (단일 발송 PR-1 에서는 0 또는 1). */
    @Column(name = "failed_count", nullable = false)
    private int failedCount;

    /**
     * 실패 사유. SMTP 응답/예외 메시지 또는 {@code recipient: reason} 형식 멀티라인.
     * 평문 보관 — 운영 진단용. 민감 정보 노출 우려 시 DB 접근 통제 의존.
     */
    @Column(name = "failed_reason", columnDefinition = "TEXT")
    private String failedReason;

    /** 실제 SMTP 시도 시각 (AFTER_COMMIT 단계에서 기록). PENDING 상태에서는 null. */
    @Column(name = "dispatched_at")
    private LocalDateTime dispatchedAt;

    @Builder
    private ManualEmailDispatch(Long senderUserSeq,
                                RecipientType recipientType,
                                Long recipientUserSeq,
                                String recipientEmail,
                                Long relatedApplicationSeq,
                                String subject,
                                String bodyText,
                                BodyFormat bodyFormat,
                                String categoryTag) {
        this.senderUserSeq = senderUserSeq;
        this.recipientType = recipientType;
        this.recipientUserSeq = recipientUserSeq;
        this.recipientEmail = recipientEmail;
        this.relatedApplicationSeq = relatedApplicationSeq;
        this.subject = subject;
        this.bodyText = bodyText;
        this.bodyFormat = bodyFormat != null ? bodyFormat : BodyFormat.PLAIN_TEXT;
        this.categoryTag = categoryTag;
        // 신규 row 는 항상 PENDING 으로 시작 — AFTER_COMMIT 리스너가 SMTP 결과에 따라 갱신.
        this.dispatchStatus = DispatchStatus.PENDING;
        this.sentCount = 0;
        this.failedCount = 0;
    }

    /**
     * SMTP 발송 성공으로 마킹. 단일 수신자 기준 sentCount=1.
     */
    public void markSent(LocalDateTime dispatchedAt) {
        this.dispatchStatus = DispatchStatus.SENT;
        this.sentCount = 1;
        this.failedCount = 0;
        this.failedReason = null;
        this.dispatchedAt = dispatchedAt;
    }

    /**
     * SMTP 발송 실패로 마킹. 단일 수신자 기준 failedCount=1.
     */
    public void markFailed(LocalDateTime dispatchedAt, String reason) {
        this.dispatchStatus = DispatchStatus.FAILED;
        this.sentCount = 0;
        this.failedCount = 1;
        this.failedReason = reason;
        this.dispatchedAt = dispatchedAt;
    }
}

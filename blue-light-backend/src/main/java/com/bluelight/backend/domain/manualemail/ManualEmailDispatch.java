package com.bluelight.backend.domain.manualemail;

import com.bluelight.backend.common.json.JsonLongListConverter;
import com.bluelight.backend.common.json.JsonStringListConverter;
import com.bluelight.backend.domain.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import java.util.ArrayList;
import java.util.List;

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
                @Index(name = "idx_manual_email_application", columnList = "related_application_seq"),
                // PR-2: MULTI 멱등성 — sender + recipientHash + 시간 윈도우 lookup 인덱스.
                @Index(name = "idx_manual_email_recipient_hash", columnList = "sender_user_seq, recipient_hash, created_at DESC")
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
     *
     * <p><b>MULTI 시 의미</b> (PR-2): 다수 수신자의 첫 번째(또는 대표) 이메일을 저장하여 단일
     * 수신자 코드 경로 호환성을 유지한다. 전체 목록은 {@link #recipientEmailsJson} 또는
     * {@link #recipientUserSeqsJson} 에서 조회한다.</p>
     */
    @Column(name = "recipient_email", nullable = false, length = 254)
    private String recipientEmail;

    /**
     * MULTI 수신자: 시스템 사용자(APPLICANT/LEW) user_seq 목록.
     *
     * <p>스펙: {@code admin-manual-email-spec.md} §4 (PR-2). 단일 발송(APPLICANT/LEW/EXTERNAL)
     * 케이스에서는 null. MULTI 발송 시 시스템 사용자 부분만 채워진다 — 외부 이메일은
     * {@link #recipientEmailsJson} 에 별도 보관.</p>
     *
     * <p>{@link JsonLongListConverter} 가 직렬화/역직렬화. DB 컬럼은 TEXT (JSON 문자열).
     * MySQL 8 의 JSON 타입과 호환되며, MySQL 5.7 이하에서도 TEXT 로 동작.</p>
     */
    @Convert(converter = JsonLongListConverter.class)
    @Column(name = "recipient_user_seqs_json", columnDefinition = "TEXT")
    private List<Long> recipientUserSeqsJson;

    /**
     * MULTI 수신자: 외부(EXTERNAL) 이메일 + 시스템 사용자 lookup 결과 이메일을 합친 전체 발송
     * 대상 목록. 단일 발송에서는 null. 멱등성 해시({@link #recipientHash}) 와 listener loop 의
     * 단일 정본이다.
     *
     * <p>스펙: {@code admin-manual-email-spec.md} §4 (PR-2). 정렬 후 저장(소문자 + 트림) — 동일
     * 수신자 set 의 멱등성 비교 안정화.</p>
     */
    @Convert(converter = JsonStringListConverter.class)
    @Column(name = "recipient_emails_json", columnDefinition = "TEXT")
    private List<String> recipientEmailsJson;

    /**
     * 멱등성 해시 — 정렬된 수신자 이메일 리스트 + subject + bodyText 의 SHA-256.
     *
     * <p>스펙: PR-2 D3=B 확장. 단일/다수 수신자 양쪽에서 30초 윈도우 내 동일 해시를 검출.</p>
     *
     * <p>저장 형식: 64자 hex (SHA-256 → 32 bytes → hex). PR-1 row 들은 마이그레이션에서 단일
     * 수신자 기반 backfill (sender + 단일 recipient + subject + body 해시)로 채워진다.</p>
     */
    @Column(name = "recipient_hash", length = 64)
    private String recipientHash;

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
                                List<Long> recipientUserSeqsJson,
                                List<String> recipientEmailsJson,
                                String recipientHash,
                                Long relatedApplicationSeq,
                                String subject,
                                String bodyText,
                                BodyFormat bodyFormat,
                                String categoryTag) {
        this.senderUserSeq = senderUserSeq;
        this.recipientType = recipientType;
        this.recipientUserSeq = recipientUserSeq;
        this.recipientEmail = recipientEmail;
        this.recipientUserSeqsJson = recipientUserSeqsJson;
        this.recipientEmailsJson = recipientEmailsJson;
        this.recipientHash = recipientHash;
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

    /**
     * PR-2 MULTI 발송 결과 마킹.
     *
     * <p>스펙: {@code admin-manual-email-spec.md} §4 / AC-A4. failedCount/sentCount 합산은
     * 호출자(listener)가 loop 후 한 번에 결정한다.</p>
     *
     * <p>status 결정 규칙:</p>
     * <ul>
     *   <li>{@code failedCount == 0} → {@link DispatchStatus#SENT}</li>
     *   <li>{@code failedCount == total} → {@link DispatchStatus#FAILED}</li>
     *   <li>그 외(부분 실패) → {@link DispatchStatus#PARTIAL_FAILED}</li>
     * </ul>
     *
     * @param dispatchedAt 모든 수신자 처리 완료 시각 (loop 종료 시점)
     * @param sentCount    SMTP 발송 성공 수
     * @param failedCount  SMTP 발송 실패 수
     * @param failedReason 실패한 수신자별 멀티라인 사유 ({@code "email: error"} 형식). 모두 성공 시 null.
     */
    public void markBatchResult(LocalDateTime dispatchedAt,
                                int sentCount,
                                int failedCount,
                                String failedReason) {
        int total = sentCount + failedCount;
        if (total <= 0) {
            // 빈 batch — 정의상 도달 불가. 안전망: PENDING 유지하지 않고 FAILED 로 마킹.
            this.dispatchStatus = DispatchStatus.FAILED;
        } else if (failedCount == 0) {
            this.dispatchStatus = DispatchStatus.SENT;
        } else if (sentCount == 0) {
            this.dispatchStatus = DispatchStatus.FAILED;
        } else {
            this.dispatchStatus = DispatchStatus.PARTIAL_FAILED;
        }
        this.sentCount = sentCount;
        this.failedCount = failedCount;
        this.failedReason = failedCount == 0 ? null : failedReason;
        this.dispatchedAt = dispatchedAt;
    }

    /**
     * 전체 발송 대상 이메일 목록을 반환. 단일/다수 발송 모두를 통합 처리하는 listener 와
     * 응답 DTO 매퍼가 사용한다.
     *
     * <p>우선순위:</p>
     * <ol>
     *   <li>{@link #recipientEmailsJson} 이 채워져 있으면 그대로 반환 (PR-2 MULTI).</li>
     *   <li>그렇지 않으면 {@link #recipientEmail} 단일 항목 리스트로 wrapping (PR-1 단일).</li>
     * </ol>
     *
     * <p>리스트는 immutable 가깝게 — 호출자가 수정해도 엔티티 상태에는 영향 없다.</p>
     */
    public List<String> resolveAllRecipientEmails() {
        if (this.recipientEmailsJson != null && !this.recipientEmailsJson.isEmpty()) {
            return new ArrayList<>(this.recipientEmailsJson);
        }
        if (this.recipientEmail == null) return List.of();
        return List.of(this.recipientEmail);
    }
}

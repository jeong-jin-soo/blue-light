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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 알림 Outbox row — 모든 채널(IN_APP/EMAIL/WHATSAPP) 공용.
 *
 * <p>도메인 트랜잭션 내부에서 PENDING 상태로 적재되고, AFTER_COMMIT 단계에서 채널 어댑터가
 * 외부 호출 후 status 만 갱신한다. ManualEmailDispatch 의 outbox 패턴을 일반화한 구조.</p>
 *
 * <h2>설계 메모</h2>
 * <ul>
 *   <li><b>Soft delete 미적용</b> — 감사 무결성 (ManualEmailDispatch 와 동일). BaseEntity 의
 *       {@code deletedAt} 컬럼은 보존만 한다.</li>
 *   <li><b>idempotencyKey UNIQUE</b> — 중복 발송 1차 가드. 동일 키로 두 번 적재되면 DB 제약 위반.</li>
 *   <li><b>상태 전이는 도메인 메서드로 한정</b> — {@link #markSending}, {@link #markSent},
 *       {@link #markFailed}, {@link #markDead}, {@link #markSkipped}.</li>
 * </ul>
 */
@Entity
@Table(
        name = "notification_outbox",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_outbox_idem", columnNames = {"idempotency_key"})
        },
        indexes = {
                @Index(name = "idx_outbox_due", columnList = "status, next_attempt_at"),
                @Index(name = "idx_outbox_ref", columnList = "reference_type, reference_id"),
                @Index(name = "idx_outbox_user", columnList = "user_seq, created_at DESC")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationOutbox extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "outbox_seq")
    private Long outboxSeq;

    @Column(name = "idempotency_key", nullable = false, length = 160)
    private String idempotencyKey;

    @Column(name = "user_seq", nullable = false)
    private Long userSeq;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private NotificationChannel channel;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(name = "template_code", nullable = false, length = 80)
    private String templateCode;

    @Column(name = "locale", nullable = false, length = 10)
    private String locale;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "reference_type", length = 50)
    private String referenceType;

    @Column(name = "reference_id")
    private Long referenceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    /**
     * 발생 출처 — PRODUCTION(기본) vs ADMIN_TEST.
     * {@link NotificationSource#ADMIN_TEST} 는 사용자 인박스 unread_count 에서 제외된다 (PR-T4).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private NotificationSource source;

    /**
     * 테스트 발송 플래그 — {@code source=ADMIN_TEST} 와 1:1 정합이지만 인덱스/필터 편의용으로 boolean 으로도 보관.
     * 기본값 false.
     */
    @Column(name = "is_test", nullable = false)
    private boolean isTest;

    /**
     * 렌더링 경고 — TemplateRenderer 가 발견한 missing keys 등 비치명적 이슈를 JSON 으로 기록.
     * 예: {@code {"missingKeys":["foo","bar"]}}. admin UI 가 가시화.
     */
    @Column(name = "render_warnings_json", columnDefinition = "TEXT")
    private String renderWarningsJson;

    @Builder
    public NotificationOutbox(String idempotencyKey,
                              Long userSeq,
                              NotificationChannel channel,
                              String eventType,
                              String templateCode,
                              String locale,
                              String payloadJson,
                              String referenceType,
                              Long referenceId,
                              NotificationSource source,
                              boolean isTest,
                              String renderWarningsJson) {
        this.idempotencyKey = idempotencyKey;
        this.userSeq = userSeq;
        this.channel = channel;
        this.eventType = eventType;
        this.templateCode = templateCode;
        this.locale = locale != null ? locale : "en";
        this.payloadJson = payloadJson;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.status = OutboxStatus.PENDING;
        this.attemptCount = 0;
        this.source = source != null ? source : NotificationSource.PRODUCTION;
        this.isTest = isTest;
        this.renderWarningsJson = renderWarningsJson;
    }

    /** 외부 호출 직전 — 동시성 가드. */
    public void markSending() {
        this.status = OutboxStatus.SENDING;
        this.attemptCount += 1;
    }

    /** 외부 호출 성공 — 채널 측 ack 수신. */
    public void markSent() {
        this.status = OutboxStatus.SENT;
        this.sentAt = LocalDateTime.now();
        this.lastError = null;
    }

    /** 외부 호출 실패 — 재시도 가능. nextAttemptAt 은 지수 백오프 정책이 산정. */
    public void markFailed(String error, LocalDateTime nextAttemptAt) {
        this.status = OutboxStatus.FAILED;
        this.lastError = error;
        this.nextAttemptAt = nextAttemptAt;
    }

    /** 최대 재시도 초과 또는 영구 실패 — 자동 재시도 중단. */
    public void markDead(String error) {
        this.status = OutboxStatus.DEAD;
        this.lastError = error;
        this.nextAttemptAt = null;
    }

    /** 발송 전 가드(옵트아웃·미검증 등)에 의해 생략. */
    public void markSkipped(String reason) {
        this.status = OutboxStatus.SKIPPED;
        this.lastError = reason;
        this.nextAttemptAt = null;
    }
}

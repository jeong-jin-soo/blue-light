package com.bluelight.backend.domain.notification.whatsapp;

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
 * WhatsApp 발송 로그 — outbox row 와 1:1 매핑되는 채널 특화 메타데이터.
 *
 * <h2>PDPA 메모</h2>
 * <ul>
 *   <li><b>본문 저장 금지</b> — {@link #payloadJson} 은 변수 슬롯만 저장. 완성된 메시지 본문은
 *       template 측에서 재구성 가능하므로 중복 저장 불필요 (최소화 원칙).</li>
 *   <li><b>phone_e164 평문 저장</b> — 로그 조회용. 필드 단위 암호화는 후속 PR (security 권고).</li>
 *   <li><b>Soft delete 미적용</b> — 감사 무결성 (ManualEmailDispatch / NotificationOutbox 와 동일).</li>
 * </ul>
 */
@Entity
@Table(
        name = "whatsapp_message_log",
        indexes = {
                @Index(name = "idx_wa_provider_msg", columnList = "provider_message_id"),
                @Index(name = "idx_wa_user_status", columnList = "user_seq, status, created_at DESC"),
                @Index(name = "idx_wa_outbox", columnList = "outbox_seq")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WhatsappMessageLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_seq")
    private Long logSeq;

    @Column(name = "outbox_seq", nullable = false)
    private Long outboxSeq;

    @Column(name = "user_seq", nullable = false)
    private Long userSeq;

    @Column(name = "phone_e164", nullable = false, length = 20)
    private String phoneE164;

    @Column(name = "template_code", nullable = false, length = 80)
    private String templateCode;

    @Column(name = "template_locale", nullable = false, length = 10)
    private String templateLocale;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private WhatsappProvider provider;

    @Column(name = "provider_message_id", length = 120)
    private String providerMessageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WhatsappDeliveryStatus status;

    @Column(name = "error_code", length = 60)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Builder
    public WhatsappMessageLog(Long outboxSeq,
                              Long userSeq,
                              String phoneE164,
                              String templateCode,
                              String templateLocale,
                              String payloadJson,
                              WhatsappProvider provider) {
        this.outboxSeq = outboxSeq;
        this.userSeq = userSeq;
        this.phoneE164 = phoneE164;
        this.templateCode = templateCode;
        this.templateLocale = templateLocale;
        this.payloadJson = payloadJson;
        this.provider = provider;
        this.status = WhatsappDeliveryStatus.QUEUED;
    }

    /** provider 에 enqueue 완료 (provider_message_id 수신). */
    public void markQueued(String providerMessageId) {
        this.providerMessageId = providerMessageId;
        this.status = WhatsappDeliveryStatus.QUEUED;
    }

    /** provider webhook: 발송 완료. */
    public void markSent() {
        this.status = WhatsappDeliveryStatus.SENT;
        this.sentAt = LocalDateTime.now();
    }

    /** provider webhook: 단말 수신. */
    public void markDelivered() {
        this.status = WhatsappDeliveryStatus.DELIVERED;
        this.deliveredAt = LocalDateTime.now();
    }

    /** provider webhook: 사용자 열람 (read receipt 활성 시). */
    public void markRead() {
        this.status = WhatsappDeliveryStatus.READ;
        this.readAt = LocalDateTime.now();
    }

    /** provider 거절 또는 webhook 실패 보고. */
    public void markFailed(String errorCode, String errorMessage) {
        this.status = WhatsappDeliveryStatus.FAILED;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }
}

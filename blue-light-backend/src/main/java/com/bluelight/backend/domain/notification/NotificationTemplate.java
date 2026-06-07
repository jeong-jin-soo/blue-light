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
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * 알림 템플릿 카탈로그 — (template_code, channel, locale) 단위.
 *
 * <p>CLAUDE.md §설계 원칙 — 코드 하드코딩 금지. 본 테이블이 템플릿의 단일 정본.
 * WhatsApp 은 Meta/BSP 측 사전 승인된 template name 을 {@link #providerTemplateName} 컬럼이 매핑한다.</p>
 *
 * <p>{@link #variablesJson} 은 {@code {{1}}, {{2}}, ...} 변수 슬롯 메타데이터 (JSON 배열).
 * 예: {@code ["applicantName","amount","applicationCode"]} — 발송 시 payload 검증에 사용.</p>
 */
@Entity
@Table(
        name = "notification_templates",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_notif_template",
                        columnNames = {"template_code", "channel", "locale"})
        },
        indexes = {
                @Index(name = "idx_notif_template_code", columnList = "template_code")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLDelete(sql = "UPDATE notification_templates SET deleted_at = NOW() WHERE template_seq = ?")
@SQLRestriction("deleted_at IS NULL")
public class NotificationTemplate extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "template_seq")
    private Long templateSeq;

    @Column(name = "template_code", nullable = false, length = 80)
    private String templateCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private NotificationChannel channel;

    @Column(name = "locale", nullable = false, length = 10)
    private String locale;

    @Column(name = "provider_template_name", length = 120)
    private String providerTemplateName;

    @Column(name = "subject", length = 200)
    private String subject;

    @Column(name = "body_text", nullable = false, columnDefinition = "TEXT")
    private String bodyText;

    @Column(name = "variables_json", columnDefinition = "TEXT")
    private String variablesJson;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /**
     * 낙관락 버전 — admin 동시 편집 시 stale write 차단.
     * GET 응답 ETag, PATCH 요청 If-Match 헤더와 1:1 매핑.
     * (스펙 §6.3)
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** 카탈로그 ID — 예: {@code A-17} (카피북 §0 식별자). nullable: 시드 미연결 row 허용. */
    @Column(name = "catalog_meta_key", length = 60)
    private String catalogMetaKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 30)
    private NotificationCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 20)
    private NotificationSeverity severity;

    /** 수신자 역할 — comma-separated, 예: {@code APPLICANT,LEW}. D-5 read 범위 필터에 사용. */
    @Column(name = "recipient_roles", length = 200)
    private String recipientRoles;

    @Builder
    public NotificationTemplate(String templateCode,
                                NotificationChannel channel,
                                String locale,
                                String providerTemplateName,
                                String subject,
                                String bodyText,
                                String variablesJson,
                                boolean enabled,
                                String catalogMetaKey,
                                NotificationCategory category,
                                NotificationSeverity severity,
                                String recipientRoles) {
        this.templateCode = templateCode;
        this.channel = channel;
        this.locale = locale;
        this.providerTemplateName = providerTemplateName;
        this.subject = subject;
        this.bodyText = bodyText;
        this.variablesJson = variablesJson;
        this.enabled = enabled;
        this.catalogMetaKey = catalogMetaKey;
        this.category = category;
        this.severity = severity;
        this.recipientRoles = recipientRoles;
    }

    public void enable() {
        this.enabled = true;
    }

    public void disable() {
        this.enabled = false;
    }

    public void updateBody(String subject, String bodyText, String variablesJson) {
        this.subject = subject;
        this.bodyText = bodyText;
        this.variablesJson = variablesJson;
    }

    public void updateProviderTemplateName(String providerTemplateName) {
        this.providerTemplateName = providerTemplateName;
    }

    /**
     * Draft approve 시 본 row 를 새 스냅샷으로 덮어쓴다. PR-T2 의 publish 경로에서 호출.
     * version 필드는 JPA 가 자동 증가시킨다.
     */
    public void applyPublishedSnapshot(String subject,
                                       String bodyText,
                                       String variablesJson,
                                       String providerTemplateName,
                                       NotificationCategory category,
                                       NotificationSeverity severity,
                                       String recipientRoles) {
        this.subject = subject;
        this.bodyText = bodyText;
        this.variablesJson = variablesJson;
        this.providerTemplateName = providerTemplateName;
        this.category = category;
        this.severity = severity;
        this.recipientRoles = recipientRoles;
    }
}

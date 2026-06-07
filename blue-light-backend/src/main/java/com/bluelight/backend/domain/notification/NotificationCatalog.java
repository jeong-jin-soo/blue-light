package com.bluelight.backend.domain.notification;

import com.bluelight.backend.domain.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * 알림 카탈로그 메타 — {@code template_code} 단위로 허용 변수·기본 카테고리·강제 토큰을 정의한다.
 *
 * <p><b>역할</b>: {@link NotificationTemplate}이 "실제 카피"라면, 본 엔티티는 "카피가 따라야 할 스키마".
 * {@code TemplateLinter}(L1)가 본 테이블의 {@link #allowedVariablesJson}과 본문 {@code {{var}}}를 대조하여
 * 미정의 변수 사용을 저장 시점에 차단한다. {@link #requiredTokensJson}은 카테고리별 강제 토큰
 * (PAYMENT 의 {@code {{paynowUen}}}, MARKETING 의 {@code {{optOutUrl}}} 등)을 정의한다.</p>
 *
 * <p><b>SSOT 원칙</b>: 카탈로그 메타(어떤 코드가 어떤 변수를 받는가)는 코드 상수 아닌 본 테이블에 둔다.
 * 신규 알림 추가 시 admin 이 콘솔에서 변수 풀을 보강할 수 있도록 — CLAUDE.md §설계 원칙 §1.</p>
 *
 * <p>스펙: {@code doc/Project Analysis/notification-template-manager-spec.md} §5.4.</p>
 */
@Entity
@Table(
        name = "notification_catalog",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_notif_catalog_code", columnNames = {"template_code"})
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLDelete(sql = "UPDATE notification_catalog SET deleted_at = NOW() WHERE catalog_seq = ?")
@SQLRestriction("deleted_at IS NULL")
public class NotificationCatalog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "catalog_seq")
    private Long catalogSeq;

    /** 카탈로그 ID — 예: {@code A-17} (카피북 §0 식별자 또는 NotificationType enum 값). */
    @Column(name = "template_code", nullable = false, length = 80)
    private String templateCode;

    /** 허용 변수 화이트리스트 JSON 배열 — 예: {@code ["applicantName","amount","publicCode"]}. */
    @Column(name = "allowed_variables_json", nullable = false, columnDefinition = "TEXT")
    private String allowedVariablesJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_category", nullable = false, length = 30)
    private NotificationCategory defaultCategory;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_severity", nullable = false, length = 20)
    private NotificationSeverity defaultSeverity;

    /** 기본 수신자 역할 — comma-separated, 예: {@code APPLICANT,LEW}. */
    @Column(name = "default_recipient_roles", nullable = false, length = 200)
    private String defaultRecipientRoles;

    @Column(name = "description", length = 500)
    private String description;

    /**
     * 발송 트리거 — 이 코드를 발화하는 기능/호출부. Admin UI 가 "어느 기능이 이 템플릿을 쏘는지"
     * 표시하는 데 사용한다. 카피북(notification-copy-templates.en.md) 각 카드의 {@code Trigger}
     * 필드가 SSOT. 예: {@code AdminPaymentService.confirmPayment}.
     */
    @Column(name = "trigger_ref", length = 255)
    private String triggerRef;

    /**
     * 카테고리별 강제 토큰 JSON 배열 — lint L4/L7 검증 대상.
     * 예: PAYMENT 카테고리는 {@code ["{{paynowUen}}","{{paynowReference}}"]},
     * MARKETING 은 {@code ["{{optOutUrl}}"]}.
     */
    @Column(name = "required_tokens_json", columnDefinition = "TEXT")
    private String requiredTokensJson;

    @Builder
    public NotificationCatalog(String templateCode,
                               String allowedVariablesJson,
                               NotificationCategory defaultCategory,
                               NotificationSeverity defaultSeverity,
                               String defaultRecipientRoles,
                               String description,
                               String requiredTokensJson,
                               String triggerRef) {
        this.templateCode = templateCode;
        this.allowedVariablesJson = allowedVariablesJson;
        this.defaultCategory = defaultCategory;
        this.defaultSeverity = defaultSeverity;
        this.defaultRecipientRoles = defaultRecipientRoles;
        this.description = description;
        this.requiredTokensJson = requiredTokensJson;
        this.triggerRef = triggerRef;
    }

    /** Admin UI 에서 카탈로그 정의를 수정할 때 사용. (실제 템플릿 본문은 별도 publish 워크플로) */
    public void updateMetadata(String allowedVariablesJson,
                               NotificationCategory defaultCategory,
                               NotificationSeverity defaultSeverity,
                               String defaultRecipientRoles,
                               String description,
                               String requiredTokensJson,
                               String triggerRef) {
        this.allowedVariablesJson = allowedVariablesJson;
        this.defaultCategory = defaultCategory;
        this.defaultSeverity = defaultSeverity;
        this.defaultRecipientRoles = defaultRecipientRoles;
        this.description = description;
        this.requiredTokensJson = requiredTokensJson;
        this.triggerRef = triggerRef;
    }
}

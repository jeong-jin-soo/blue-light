package com.bluelight.backend.api.admin.notification.template.dto;

import com.bluelight.backend.domain.notification.NotificationCategory;
import com.bluelight.backend.domain.notification.NotificationChannel;
import com.bluelight.backend.domain.notification.NotificationSeverity;
import com.bluelight.backend.domain.notification.NotificationTemplate;

import java.time.LocalDateTime;

/** Detail 화면 — 본문 포함 전 필드 + ETag 값(version) 응답. */
public record NotificationTemplateDetailResponse(
        Long templateSeq,
        String templateCode,
        NotificationChannel channel,
        String locale,
        String subject,
        String bodyText,
        String variablesJson,
        String providerTemplateName,
        boolean enabled,
        Long version,
        String catalogMetaKey,
        NotificationCategory category,
        NotificationSeverity severity,
        String recipientRoles,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Long updatedBy
) {
    public static NotificationTemplateDetailResponse from(NotificationTemplate t) {
        return new NotificationTemplateDetailResponse(
                t.getTemplateSeq(),
                t.getTemplateCode(),
                t.getChannel(),
                t.getLocale(),
                t.getSubject(),
                t.getBodyText(),
                t.getVariablesJson(),
                t.getProviderTemplateName(),
                t.isEnabled(),
                t.getVersion(),
                t.getCatalogMetaKey(),
                t.getCategory(),
                t.getSeverity(),
                t.getRecipientRoles(),
                t.getCreatedAt(),
                t.getUpdatedAt(),
                t.getUpdatedBy()
        );
    }
}

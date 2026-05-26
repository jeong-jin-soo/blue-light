package com.bluelight.backend.api.admin.notification.template.dto;

import com.bluelight.backend.domain.notification.NotificationCategory;
import com.bluelight.backend.domain.notification.NotificationChannel;
import com.bluelight.backend.domain.notification.NotificationSeverity;
import com.bluelight.backend.domain.notification.NotificationTemplate;

import java.time.LocalDateTime;

/** List 화면 row — PR-T3 spec §7.2 매트릭스 도트 뷰의 1행. */
public record NotificationTemplateListItemResponse(
        Long templateSeq,
        String templateCode,
        NotificationChannel channel,
        String locale,
        String subject,
        boolean enabled,
        Long version,
        String catalogMetaKey,
        NotificationCategory category,
        NotificationSeverity severity,
        String recipientRoles,
        LocalDateTime updatedAt,
        Long updatedBy
) {
    public static NotificationTemplateListItemResponse from(NotificationTemplate t) {
        return new NotificationTemplateListItemResponse(
                t.getTemplateSeq(),
                t.getTemplateCode(),
                t.getChannel(),
                t.getLocale(),
                t.getSubject(),
                t.isEnabled(),
                t.getVersion(),
                t.getCatalogMetaKey(),
                t.getCategory(),
                t.getSeverity(),
                t.getRecipientRoles(),
                t.getUpdatedAt(),
                t.getUpdatedBy()
        );
    }
}

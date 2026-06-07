package com.bluelight.backend.api.admin.notification.template.dto;

import com.bluelight.backend.domain.notification.NotificationCatalog;
import com.bluelight.backend.domain.notification.NotificationCategory;
import com.bluelight.backend.domain.notification.NotificationSeverity;

/**
 * 카탈로그 메타 응답 — UI 가 변수 자동완성·기본 카테고리 추론·강제 토큰 가이드에 사용.
 */
public record CatalogEntryResponse(
        Long catalogSeq,
        String templateCode,
        String allowedVariablesJson,
        NotificationCategory defaultCategory,
        NotificationSeverity defaultSeverity,
        String defaultRecipientRoles,
        String description,
        String requiredTokensJson,
        String triggerRef
) {
    public static CatalogEntryResponse from(NotificationCatalog c) {
        return new CatalogEntryResponse(
                c.getCatalogSeq(),
                c.getTemplateCode(),
                c.getAllowedVariablesJson(),
                c.getDefaultCategory(),
                c.getDefaultSeverity(),
                c.getDefaultRecipientRoles(),
                c.getDescription(),
                c.getRequiredTokensJson(),
                c.getTriggerRef()
        );
    }
}

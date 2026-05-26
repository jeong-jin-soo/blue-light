package com.bluelight.backend.api.admin.notification.template.dto;

import com.bluelight.backend.api.admin.notification.template.NotificationTemplateAdminService.DraftMutationInput;
import com.bluelight.backend.domain.notification.NotificationCategory;
import com.bluelight.backend.domain.notification.NotificationChannel;
import com.bluelight.backend.domain.notification.NotificationSeverity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Draft 신규 생성 요청. {@code templateSeq} 가 null 이면 새 템플릿 코드, non-null 이면
 * 기존 template 의 수정 draft.
 */
public record CreateDraftRequest(
        Long templateSeq,
        @NotBlank @Size(max = 80) String templateCode,
        @NotNull NotificationChannel channel,
        @NotBlank @Size(max = 10) String locale,
        @Size(max = 200) String subject,
        @NotBlank String body,
        String variablesJson,
        @Size(max = 120) String providerTemplateName,
        NotificationCategory category,
        NotificationSeverity severity,
        @Size(max = 200) String recipientRoles,
        @Size(max = 500) String submissionNote
) {
    public DraftMutationInput toInput() {
        return new DraftMutationInput(
                templateSeq, templateCode, channel, locale,
                subject, body, variablesJson, providerTemplateName,
                category, severity, recipientRoles, submissionNote
        );
    }
}

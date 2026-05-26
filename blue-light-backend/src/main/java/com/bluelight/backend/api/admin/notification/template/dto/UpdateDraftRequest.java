package com.bluelight.backend.api.admin.notification.template.dto;

import com.bluelight.backend.api.admin.notification.template.NotificationTemplateAdminService.DraftMutationInput;
import com.bluelight.backend.domain.notification.NotificationCategory;
import com.bluelight.backend.domain.notification.NotificationChannel;
import com.bluelight.backend.domain.notification.NotificationSeverity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Draft 편집 요청 — 본문/메타 일괄 갱신. (code/channel/locale 은 신규 생성 후 변경 불가)
 */
public record UpdateDraftRequest(
        @Size(max = 200) String subject,
        @NotBlank String body,
        String variablesJson,
        @Size(max = 120) String providerTemplateName,
        NotificationCategory category,
        NotificationSeverity severity,
        @Size(max = 200) String recipientRoles,
        @Size(max = 500) String submissionNote
) {
    /** existing draft 의 code/channel/locale/templateSeq 를 그대로 유지하여 DraftMutationInput 합성. */
    public DraftMutationInput toInput(Long templateSeq,
                                      String templateCode,
                                      NotificationChannel channel,
                                      String locale) {
        return new DraftMutationInput(
                templateSeq, templateCode, channel, locale,
                subject, body, variablesJson, providerTemplateName,
                category, severity, recipientRoles, submissionNote
        );
    }
}

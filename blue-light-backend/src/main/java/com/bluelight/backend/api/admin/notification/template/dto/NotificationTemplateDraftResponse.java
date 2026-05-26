package com.bluelight.backend.api.admin.notification.template.dto;

import com.bluelight.backend.domain.notification.NotificationCategory;
import com.bluelight.backend.domain.notification.NotificationChannel;
import com.bluelight.backend.domain.notification.NotificationSeverity;
import com.bluelight.backend.domain.notification.NotificationTemplateDraft;
import com.bluelight.backend.domain.notification.TemplateDraftStatus;

import java.time.LocalDateTime;

/** Draft 상세 응답 — NM 편집 화면 + SA 리뷰 큐 공용. */
public record NotificationTemplateDraftResponse(
        Long draftSeq,
        Long templateSeq,
        String templateCode,
        NotificationChannel channel,
        String locale,
        String subject,
        String bodyText,
        String variablesJson,
        String providerTemplateName,
        NotificationCategory category,
        NotificationSeverity severity,
        String recipientRoles,
        Long submittedBy,
        LocalDateTime submittedAt,
        String submissionNote,
        TemplateDraftStatus status,
        Long reviewedBy,
        LocalDateTime reviewedAt,
        String reviewNote
) {
    public static NotificationTemplateDraftResponse from(NotificationTemplateDraft d) {
        return new NotificationTemplateDraftResponse(
                d.getDraftSeq(),
                d.getTemplateSeq(),
                d.getTemplateCode(),
                d.getChannel(),
                d.getLocale(),
                d.getSubject(),
                d.getBodyText(),
                d.getVariablesJson(),
                d.getProviderTemplateName(),
                d.getCategory(),
                d.getSeverity(),
                d.getRecipientRoles(),
                d.getSubmittedBy(),
                d.getSubmittedAt(),
                d.getSubmissionNote(),
                d.getStatus(),
                d.getReviewedBy(),
                d.getReviewedAt(),
                d.getReviewNote()
        );
    }
}

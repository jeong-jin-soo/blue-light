package com.bluelight.backend.api.admin.notification.template.dto;

import com.bluelight.backend.domain.notification.NotificationTemplateHistory;
import com.bluelight.backend.domain.notification.TemplateChangeType;

import java.time.LocalDateTime;

/**
 * 템플릿 변경 이력 한 항목 — UI History 탭 표시용.
 *
 * <p>diff/snapshot JSON 은 raw String 으로 전달 — 프론트가 파싱·diff 시각화 책임.</p>
 */
public record HistoryItemResponse(
        Long historySeq,
        Long templateSeq,
        TemplateChangeType changeType,
        String diffJson,
        String beforeSnapshotJson,
        String afterSnapshotJson,
        String changeReason,
        Long actorUserSeq,
        String actorIp,
        LocalDateTime changedAt
) {
    public static HistoryItemResponse from(NotificationTemplateHistory h) {
        return new HistoryItemResponse(
                h.getHistorySeq(),
                h.getTemplateSeq(),
                h.getChangeType(),
                h.getDiffJson(),
                h.getBeforeSnapshotJson(),
                h.getAfterSnapshotJson(),
                h.getChangeReason(),
                h.getActorUserSeq(),
                h.getActorIp(),
                h.getChangedAt()
        );
    }
}

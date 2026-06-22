package com.bluelight.backend.api.audit;

import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.audit.AuditLog;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 신청 건별 활동 타임라인 항목 (audit_logs 1행 = 타임라인 1개).
 * <p>"누가(actor) · 언제(occurredAt) · 무엇을(action/description)" + 자동 동작(isSystem) 표현.
 */
@Getter
@Builder
public class ApplicationActivityResponse {

    private Long auditLogSeq;
    private LocalDateTime occurredAt;

    private AuditAction action;
    private AuditCategory actionCategory;

    /** 행위자 — 시스템(자동) 동작이면 actorSeq=null, isSystem=true. */
    private Long actorSeq;
    private String actorEmail;
    private String actorRole;
    private boolean system;

    private String description;
    private String beforeValue;
    private String afterValue;

    private String entityType;
    private String entityId;

    /** 실패 이벤트 식별용 (예: 4xx/5xx). null 이면 미기록(서버 내부 호출 등). */
    private Integer httpStatus;

    public static ApplicationActivityResponse from(AuditLog log) {
        boolean isSystem = AuditLogService.SYSTEM_ACTOR_ROLE.equals(log.getUserRole())
                || (log.getUserSeq() == null
                    && AuditLogService.SYSTEM_ACTOR_EMAIL.equals(log.getUserEmail()));
        return ApplicationActivityResponse.builder()
                .auditLogSeq(log.getAuditLogSeq())
                .occurredAt(log.getCreatedAt())
                .action(log.getAction())
                .actionCategory(log.getActionCategory())
                .actorSeq(log.getUserSeq())
                .actorEmail(log.getUserEmail())
                .actorRole(log.getUserRole())
                .system(isSystem)
                .description(log.getDescription())
                .beforeValue(log.getBeforeValue())
                .afterValue(log.getAfterValue())
                .entityType(log.getEntityType())
                .entityId(log.getEntityId())
                .httpStatus(log.getHttpStatus())
                .build();
    }
}

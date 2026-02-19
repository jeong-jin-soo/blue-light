package com.bluelight.backend.api.audit;

import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.audit.AuditLog;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class AuditLogResponse {

    private Long auditLogSeq;
    private Long userSeq;
    private String userEmail;
    private String userRole;
    private AuditAction action;
    private AuditCategory actionCategory;
    private String entityType;
    private String entityId;
    private String description;
    private String beforeValue;
    private String afterValue;
    private String ipAddress;
    private String requestMethod;
    private String requestUri;
    private Integer httpStatus;
    private LocalDateTime createdAt;

    public static AuditLogResponse from(AuditLog log) {
        return AuditLogResponse.builder()
                .auditLogSeq(log.getAuditLogSeq())
                .userSeq(log.getUserSeq())
                .userEmail(log.getUserEmail())
                .userRole(log.getUserRole())
                .action(log.getAction())
                .actionCategory(log.getActionCategory())
                .entityType(log.getEntityType())
                .entityId(log.getEntityId())
                .description(log.getDescription())
                .beforeValue(log.getBeforeValue())
                .afterValue(log.getAfterValue())
                .ipAddress(log.getIpAddress())
                .requestMethod(log.getRequestMethod())
                .requestUri(log.getRequestUri())
                .httpStatus(log.getHttpStatus())
                .createdAt(log.getCreatedAt())
                .build();
    }
}

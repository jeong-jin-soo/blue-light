package com.bluelight.backend.domain.audit;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 감사 로그 엔티티 (immutable, BaseEntity 미상속)
 */
@Entity
@Table(name = "audit_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "audit_log_seq")
    private Long auditLogSeq;

    @Column(name = "user_seq")
    private Long userSeq;

    @Column(name = "user_email", length = 100)
    private String userEmail;

    @Column(name = "user_role", length = 20)
    private String userRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 50)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_category", nullable = false, length = 30)
    private AuditCategory actionCategory;

    @Column(name = "entity_type", length = 50)
    private String entityType;

    @Column(name = "entity_id", length = 50)
    private String entityId;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "before_value", columnDefinition = "JSON")
    private String beforeValue;

    @Column(name = "after_value", columnDefinition = "JSON")
    private String afterValue;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "request_method", length = 10)
    private String requestMethod;

    @Column(name = "request_uri", length = 255)
    private String requestUri;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public AuditLog(Long userSeq, String userEmail, String userRole,
                    AuditAction action, AuditCategory actionCategory,
                    String entityType, String entityId, String description,
                    String beforeValue, String afterValue,
                    String ipAddress, String userAgent,
                    String requestMethod, String requestUri, Integer httpStatus) {
        this.userSeq = userSeq;
        this.userEmail = userEmail;
        this.userRole = userRole;
        this.action = action;
        this.actionCategory = actionCategory;
        this.entityType = entityType;
        this.entityId = entityId;
        this.description = description;
        this.beforeValue = beforeValue;
        this.afterValue = afterValue;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.requestMethod = requestMethod;
        this.requestUri = requestUri;
        this.httpStatus = httpStatus;
        this.createdAt = LocalDateTime.now();
    }
}

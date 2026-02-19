package com.bluelight.backend.api.audit;

import com.bluelight.backend.domain.audit.*;
import com.bluelight.backend.domain.user.UserRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Value("${audit.retention-days:365}")
    private int retentionDays;

    /**
     * 비동기 감사 로그 기록 (AOP에서 호출)
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logAsync(Long userSeq, AuditAction action, AuditCategory category,
                         String entityType, String entityId, String description,
                         Object beforeValue, Object afterValue,
                         String ipAddress, String userAgent,
                         String requestMethod, String requestUri, Integer httpStatus) {
        try {
            String userEmail = null;
            String userRole = null;
            if (userSeq != null) {
                var userOpt = userRepository.findById(userSeq);
                if (userOpt.isPresent()) {
                    userEmail = userOpt.get().getEmail();
                    userRole = userOpt.get().getRole().name();
                }
            }

            AuditLog auditLog = AuditLog.builder()
                    .userSeq(userSeq)
                    .userEmail(userEmail)
                    .userRole(userRole)
                    .action(action)
                    .actionCategory(category)
                    .entityType(entityType)
                    .entityId(entityId)
                    .description(description)
                    .beforeValue(toJson(beforeValue))
                    .afterValue(toJson(afterValue))
                    .ipAddress(ipAddress)
                    .userAgent(userAgent)
                    .requestMethod(requestMethod)
                    .requestUri(requestUri)
                    .httpStatus(httpStatus)
                    .build();

            auditLogRepository.save(auditLog);
            log.debug("감사 로그 기록: action={}, entityType={}, entityId={}", action, entityType, entityId);
        } catch (Exception e) {
            log.error("감사 로그 비동기 저장 실패", e);
        }
    }

    /**
     * 동기 감사 로그 기록 (인증 이벤트 등)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(Long userSeq, String userEmail, String userRole,
                    AuditAction action, AuditCategory category,
                    String entityType, String entityId, String description,
                    Object beforeValue, Object afterValue,
                    String ipAddress, String userAgent,
                    String requestMethod, String requestUri, Integer httpStatus) {
        try {
            AuditLog auditLog = AuditLog.builder()
                    .userSeq(userSeq)
                    .userEmail(userEmail)
                    .userRole(userRole)
                    .action(action)
                    .actionCategory(category)
                    .entityType(entityType)
                    .entityId(entityId)
                    .description(description)
                    .beforeValue(toJson(beforeValue))
                    .afterValue(toJson(afterValue))
                    .ipAddress(ipAddress)
                    .userAgent(userAgent)
                    .requestMethod(requestMethod)
                    .requestUri(requestUri)
                    .httpStatus(httpStatus)
                    .build();

            auditLogRepository.save(auditLog);
        } catch (Exception e) {
            log.error("감사 로그 동기 저장 실패", e);
        }
    }

    /**
     * 감사 로그 검색 (SYSTEM_ADMIN 전용)
     */
    @Transactional(readOnly = true)
    public Page<AuditLogResponse> searchLogs(AuditCategory category, AuditAction action,
                                              Long userSeq, String entityType, String entityId,
                                              LocalDateTime startDate, LocalDateTime endDate,
                                              String search, Pageable pageable) {
        Page<AuditLog> page = auditLogRepository.searchAuditLogs(
                category, action, userSeq, entityType, entityId,
                startDate, endDate, search, pageable);
        return page.map(AuditLogResponse::from);
    }

    /**
     * 보존 기간 초과 감사 로그 자동 정리 (매일 새벽 3시)
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanupOldLogs() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int totalDeleted = 0;
        int batchSize = 1000;

        int deleted;
        do {
            deleted = auditLogRepository.deleteOlderThan(cutoff, batchSize);
            totalDeleted += deleted;
        } while (deleted == batchSize);

        if (totalDeleted > 0) {
            log.info("감사 로그 정리 완료: {}건 삭제 (보존 기간: {}일)", totalDeleted, retentionDays);
        }
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        if (obj instanceof String s) return s;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JacksonException e) {
            log.warn("JSON 직렬화 실패", e);
            return String.valueOf(obj);
        }
    }
}

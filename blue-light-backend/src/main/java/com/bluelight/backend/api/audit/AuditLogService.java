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
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
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

    @Value("${audit.archive-retention-years:5}")
    private int archiveRetentionYears;

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
     * 감사 로그 아카이브 (매일 새벽 3시)
     * - 1단계: retention-days(기본 365일) 초과 로그 → audit_logs_archive로 이동
     * - 2단계: 원본 테이블에서 아카이브 완료된 로그 삭제
     * - 3단계: Privacy Policy 보유 기간(기본 5년) 초과 아카이브 영구 삭제
     */
    @Scheduled(cron = "0 0 3 * * *")
    @SchedulerLock(name = "archiveAndCleanupLogs", lockAtMostFor = "30m", lockAtLeastFor = "5m")
    @Transactional
    public void archiveAndCleanupLogs() {
        int batchSize = 1000;
        LocalDateTime archiveCutoff = LocalDateTime.now().minusDays(retentionDays);

        // 1단계: 아카이브 테이블로 복사
        int totalArchived = 0;
        int archived;
        do {
            archived = auditLogRepository.archiveOlderThan(archiveCutoff, batchSize);
            totalArchived += archived;
        } while (archived == batchSize);

        // 2단계: 아카이브 완료된 원본 삭제
        int totalDeleted = 0;
        int deleted;
        do {
            deleted = auditLogRepository.deleteArchivedLogs(archiveCutoff, batchSize);
            totalDeleted += deleted;
        } while (deleted == batchSize);

        if (totalArchived > 0 || totalDeleted > 0) {
            log.info("감사 로그 아카이브 완료: {}건 아카이브, {}건 원본 삭제 (보존 기간: {}일)",
                    totalArchived, totalDeleted, retentionDays);
        }

        // 3단계: Privacy Policy 보유 기간 초과 아카이브 영구 삭제
        LocalDateTime expiryCutoff = LocalDateTime.now().minusYears(archiveRetentionYears);
        int totalExpired = 0;
        int expired;
        do {
            expired = auditLogRepository.deleteExpiredArchives(expiryCutoff, batchSize);
            totalExpired += expired;
        } while (expired == batchSize);

        if (totalExpired > 0) {
            log.info("아카이브 로그 영구 삭제: {}건 (보유 기간: {}년 초과)", totalExpired, archiveRetentionYears);
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

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

    /** SYSTEM(자동/스케줄러) 행위자 식별 라벨 — userSeq 가 없는 시스템 동작 기록용. */
    public static final String SYSTEM_ACTOR_ROLE = "SYSTEM";
    public static final String SYSTEM_ACTOR_EMAIL = "system@licensekaki.sg";

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
        String[] u = lookupUser(userSeq);
        persist(null, userSeq, u[0], u[1], action, category, entityType, entityId, description,
                beforeValue, afterValue, ipAddress, userAgent, requestMethod, requestUri, httpStatus);
    }

    /**
     * 비동기 감사 로그 기록 + 신청(Application) 명시 연결 (타임라인용)
     * - entity_id 가 자기 PK(paymentSeq·drId 등)인 경우 applicationSeq 를 명시 전달한다.
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logAsync(Long applicationSeq, Long userSeq, AuditAction action, AuditCategory category,
                         String entityType, String entityId, String description,
                         Object beforeValue, Object afterValue,
                         String ipAddress, String userAgent,
                         String requestMethod, String requestUri, Integer httpStatus) {
        String[] u = lookupUser(userSeq);
        persist(applicationSeq, userSeq, u[0], u[1], action, category, entityType, entityId, description,
                beforeValue, afterValue, ipAddress, userAgent, requestMethod, requestUri, httpStatus);
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
        persist(null, userSeq, userEmail, userRole, action, category, entityType, entityId, description,
                beforeValue, afterValue, ipAddress, userAgent, requestMethod, requestUri, httpStatus);
    }

    /**
     * 동기 감사 로그 기록 + 신청(Application) 명시 연결 (타임라인용)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(Long applicationSeq, Long userSeq, String userEmail, String userRole,
                    AuditAction action, AuditCategory category,
                    String entityType, String entityId, String description,
                    Object beforeValue, Object afterValue,
                    String ipAddress, String userAgent,
                    String requestMethod, String requestUri, Integer httpStatus) {
        persist(applicationSeq, userSeq, userEmail, userRole, action, category, entityType, entityId, description,
                beforeValue, afterValue, ipAddress, userAgent, requestMethod, requestUri, httpStatus);
    }

    /** 실제 빌드+저장 단일 진입점. 모든 log/logAsync 오버로드가 본 메서드로 수렴한다. */
    private void persist(Long applicationSeq, Long userSeq, String userEmail, String userRole,
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
                    .applicationSeq(resolveApplicationSeq(applicationSeq, entityType, entityId))
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
            log.debug("감사 로그 기록: action={}, entityType={}, entityId={}, applicationSeq={}",
                    action, entityType, entityId, auditLog.getApplicationSeq());
        } catch (Exception e) {
            log.error("감사 로그 저장 실패", e);
        }
    }

    /**
     * 신청 연결 해석: 명시값이 있으면 사용, 없으면 entityType=Application 일 때 entityId 를 파싱한다.
     * 이로써 @Auditable(entityType="Application") 경로는 별도 변경 없이 자동 연결된다.
     */
    private Long resolveApplicationSeq(Long explicit, String entityType, String entityId) {
        if (explicit != null) return explicit;
        if (entityType != null && entityType.equalsIgnoreCase("Application") && entityId != null) {
            try {
                return Long.parseLong(entityId.trim());
            } catch (NumberFormatException ignored) {
                // entityId 가 숫자가 아니면 연결 불가 — null 유지
            }
        }
        return null;
    }

    /** userSeq → [email, role]. 없으면 [null, null]. */
    private String[] lookupUser(Long userSeq) {
        if (userSeq == null) return new String[]{null, null};
        return userRepository.findById(userSeq)
                .map(u -> new String[]{u.getEmail(), u.getRole().name()})
                .orElse(new String[]{null, null});
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
     * 신청 건별 활동 타임라인 조회 (ADMIN/SYSTEM_ADMIN).
     * - audit_logs.application_seq 로 연결된 모든 이벤트를 시간 오름차순으로 반환.
     */
    @Transactional(readOnly = true)
    public java.util.List<ApplicationActivityResponse> getApplicationActivity(Long applicationSeq) {
        return auditLogRepository.findByApplicationSeqOrderByCreatedAtAsc(applicationSeq).stream()
                .map(ApplicationActivityResponse::from)
                .toList();
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

package com.bluelight.backend.api.breach;

import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.common.util.EnumParser;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.breach.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 데이터 유출 통보 서비스 (PDPA 준수)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DataBreachService {

    private final DataBreachRepository dataBreachRepository;
    private final AuditLogService auditLogService;

    /**
     * 데이터 유출 보고 생성
     */
    @Transactional
    public DataBreachResponse reportBreach(Long reportedByUserSeq, DataBreachRequest request) {
        BreachSeverity severity = EnumParser.parseNullable(
                BreachSeverity.class, request.getSeverity(), "INVALID_SEVERITY");

        DataBreachNotification breach = DataBreachNotification.builder()
                .title(request.getTitle())
                .description(request.getDescription())
                .severity(severity)
                .affectedCount(request.getAffectedCount())
                .dataTypesAffected(request.getDataTypesAffected())
                .containmentActions(request.getContainmentActions())
                .reportedBy(reportedByUserSeq)
                .build();

        dataBreachRepository.save(breach);

        // 감사 로그
        auditLogService.logAsync(
                reportedByUserSeq, AuditAction.DATA_BREACH_REPORTED, AuditCategory.DATA_PROTECTION,
                "BREACH", String.valueOf(breach.getBreachSeq()),
                "Data breach reported: " + breach.getTitle(),
                null, null,
                null, null, "POST", "/api/admin/data-breaches", 201
        );

        log.info("데이터 유출 보고 생성: breachSeq={}, title={}", breach.getBreachSeq(), breach.getTitle());
        return DataBreachResponse.from(breach);
    }

    /**
     * 유출 통보 목록 조회
     */
    public Page<DataBreachResponse> getBreaches(String statusStr, Pageable pageable) {
        Page<DataBreachNotification> page;
        if (statusStr != null && !statusStr.isBlank()) {
            BreachStatus status = EnumParser.parseNullable(
                    BreachStatus.class, statusStr, "INVALID_BREACH_STATUS");
            page = dataBreachRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
        } else {
            page = dataBreachRepository.findAllByOrderByCreatedAtDesc(pageable);
        }
        return page.map(DataBreachResponse::from);
    }

    /**
     * 유출 통보 상세 조회
     */
    public DataBreachResponse getBreach(Long breachSeq) {
        DataBreachNotification breach = findBreachOrThrow(breachSeq);
        return DataBreachResponse.from(breach);
    }

    /**
     * PDPC 통보 완료 기록
     */
    @Transactional
    public DataBreachResponse notifyPdpc(Long breachSeq, String pdpcReferenceNo, Long userSeq) {
        DataBreachNotification breach = findBreachOrThrow(breachSeq);
        breach.markPdpcNotified(pdpcReferenceNo);

        auditLogService.logAsync(
                userSeq, AuditAction.DATA_BREACH_PDPC_NOTIFIED, AuditCategory.DATA_PROTECTION,
                "BREACH", String.valueOf(breachSeq),
                "PDPC notified for breach: " + breach.getTitle(),
                null, null,
                null, null, "PUT", "/api/admin/data-breaches/" + breachSeq + "/pdpc-notify", 200
        );

        log.info("PDPC 통보 완료: breachSeq={}, refNo={}", breachSeq, pdpcReferenceNo);
        return DataBreachResponse.from(breach);
    }

    /**
     * 영향 받은 사용자 통보 완료 기록
     */
    @Transactional
    public DataBreachResponse notifyUsers(Long breachSeq, Long userSeq) {
        DataBreachNotification breach = findBreachOrThrow(breachSeq);
        breach.markUsersNotified();

        auditLogService.logAsync(
                userSeq, AuditAction.DATA_BREACH_USERS_NOTIFIED, AuditCategory.DATA_PROTECTION,
                "BREACH", String.valueOf(breachSeq),
                "Affected users notified for breach: " + breach.getTitle(),
                null, null,
                null, null, "PUT", "/api/admin/data-breaches/" + breachSeq + "/users-notify", 200
        );

        log.info("사용자 통보 완료: breachSeq={}", breachSeq);
        return DataBreachResponse.from(breach);
    }

    /**
     * 유출 해결 처리
     */
    @Transactional
    public DataBreachResponse resolveBreach(Long breachSeq, Long userSeq) {
        DataBreachNotification breach = findBreachOrThrow(breachSeq);
        breach.updateStatus(BreachStatus.RESOLVED);

        auditLogService.logAsync(
                userSeq, AuditAction.DATA_BREACH_RESOLVED, AuditCategory.DATA_PROTECTION,
                "BREACH", String.valueOf(breachSeq),
                "Data breach resolved: " + breach.getTitle(),
                null, null,
                null, null, "PUT", "/api/admin/data-breaches/" + breachSeq + "/resolve", 200
        );

        log.info("유출 해결 완료: breachSeq={}", breachSeq);
        return DataBreachResponse.from(breach);
    }

    /**
     * 차단 조치 업데이트
     */
    @Transactional
    public DataBreachResponse updateContainment(Long breachSeq, String containmentActions) {
        DataBreachNotification breach = findBreachOrThrow(breachSeq);
        breach.updateContainmentActions(containmentActions);
        return DataBreachResponse.from(breach);
    }

    private DataBreachNotification findBreachOrThrow(Long breachSeq) {
        return dataBreachRepository.findById(breachSeq)
                .orElseThrow(() -> new BusinessException(
                        "Data breach notification not found", HttpStatus.NOT_FOUND, "BREACH_NOT_FOUND"));
    }
}

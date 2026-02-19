package com.bluelight.backend.api.audit;

import com.bluelight.backend.common.util.EnumParser;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@Slf4j
@RestController
@RequestMapping("/api/admin/audit-logs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class AuditLogController {

    private final AuditLogService auditLogService;

    @GetMapping
    public ResponseEntity<Page<AuditLogResponse>> getAuditLogs(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Long userSeq,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        log.info("감사 로그 조회: category={}, action={}, search={}, page={}, size={}", category, action, search, page, size);

        int validPage = Math.max(0, page);
        int validSize = Math.min(Math.max(1, size), 100);

        AuditCategory categoryEnum = EnumParser.parseNullable(AuditCategory.class, category, "INVALID_CATEGORY");
        AuditAction actionEnum = EnumParser.parseNullable(AuditAction.class, action, "INVALID_ACTION");

        Pageable pageable = PageRequest.of(validPage, validSize);

        Page<AuditLogResponse> result = auditLogService.searchLogs(
                categoryEnum, actionEnum, userSeq, entityType, entityId,
                startDate, endDate, search, pageable);

        return ResponseEntity.ok(result);
    }
}

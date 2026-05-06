package com.bluelight.backend.api.document;

import com.bluelight.backend.api.document.dto.CreateDocumentRequestsRequest;
import com.bluelight.backend.api.document.dto.DocumentRequestDto;
import com.bluelight.backend.api.document.dto.RejectDocumentRequest;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.audit.Auditable;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Phase 3 PR#1 — LEW 서류 요청 관리 API
 *
 *   POST   /api/admin/applications/{id}/document-requests   (배치 생성)
 *   PATCH  /api/admin/document-requests/{reqId}/approve
 *   PATCH  /api/admin/document-requests/{reqId}/reject
 *   DELETE /api/admin/document-requests/{reqId}             (CANCELLED)
 *
 * 권한: ADMIN / SYSTEM_ADMIN / LEW (서비스에서 assignedLew 이중 확인 — B-4).
 * 감사: {@link Auditable} 로 5종 이벤트 자동 기록.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM_ADMIN', 'LEW')")
public class DocumentRequestAdminController {

    private final DocumentRequestService documentRequestService;

    @Auditable(
            action = AuditAction.DOCUMENT_REQUEST_CREATED,
            category = AuditCategory.APPLICATION,
            entityType = "DocumentRequest")
    @PostMapping("/api/admin/applications/{applicationId}/document-requests")
    public ResponseEntity<Map<String, Object>> createBatch(
            Authentication authentication,
            @PathVariable Long applicationId,
            @Valid @RequestBody CreateDocumentRequestsRequest request) {
        Long userSeq = (Long) authentication.getPrincipal();
        String role = authentication.getAuthorities().iterator().next().getAuthority();
        List<DocumentRequestDto> created =
                documentRequestService.createBatch(userSeq, role, applicationId, request);
        log.info("LEW batch created: applicationSeq={}, count={}, userSeq={}",
                applicationId, created.size(), userSeq);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("created", created));
    }

    @Auditable(
            action = AuditAction.DOCUMENT_REQUEST_APPROVED,
            category = AuditCategory.APPLICATION,
            entityType = "DocumentRequest")
    @PatchMapping("/api/admin/document-requests/{docRequestId}/approve")
    public ResponseEntity<DocumentRequestDto> approve(
            Authentication authentication,
            @PathVariable Long docRequestId) {
        Long userSeq = (Long) authentication.getPrincipal();
        String role = authentication.getAuthorities().iterator().next().getAuthority();
        return ResponseEntity.ok(documentRequestService.approve(userSeq, role, docRequestId));
    }

    @Auditable(
            action = AuditAction.DOCUMENT_REQUEST_REJECTED,
            category = AuditCategory.APPLICATION,
            entityType = "DocumentRequest")
    @PatchMapping("/api/admin/document-requests/{docRequestId}/reject")
    public ResponseEntity<DocumentRequestDto> reject(
            Authentication authentication,
            @PathVariable Long docRequestId,
            @Valid @RequestBody RejectDocumentRequest request) {
        Long userSeq = (Long) authentication.getPrincipal();
        String role = authentication.getAuthorities().iterator().next().getAuthority();
        return ResponseEntity.ok(documentRequestService.reject(
                userSeq, role, docRequestId, request.getRejectionReason()));
    }

    @Auditable(
            action = AuditAction.DOCUMENT_REQUEST_CANCELLED,
            category = AuditCategory.APPLICATION,
            entityType = "DocumentRequest")
    @DeleteMapping("/api/admin/document-requests/{docRequestId}")
    public ResponseEntity<DocumentRequestDto> cancel(
            Authentication authentication,
            @PathVariable Long docRequestId) {
        Long userSeq = (Long) authentication.getPrincipal();
        String role = authentication.getAuthorities().iterator().next().getAuthority();
        return ResponseEntity.ok(documentRequestService.cancel(userSeq, role, docRequestId));
    }
}

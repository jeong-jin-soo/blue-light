package com.bluelight.backend.api.document;

import com.bluelight.backend.api.document.dto.DocumentRequestDto;
import com.bluelight.backend.api.document.dto.VoluntaryUploadResponse;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.audit.Auditable;
import com.bluelight.backend.domain.document.DocumentRequestStatus;
import com.bluelight.backend.security.GenericRateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 신청서 단위 DocumentRequest API (Phase 2 자발적 업로드)
 *
 *   GET    /api/applications/{id}/document-requests
 *   POST   /api/applications/{id}/documents          (multipart)
 *   DELETE /api/applications/{id}/documents/{drId}
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class DocumentRequestController {

    private final DocumentRequestService documentRequestService;
    private final GenericRateLimiter rateLimiter;

    /** 자발적 업로드 Rate Limit: 사용자당 10분 내 30회 (FileController와 동일 정책) */
    private static final String RATE_TYPE = "FILE_UPLOAD";
    private static final int UPLOAD_MAX = 30;
    private static final long UPLOAD_WINDOW_MIN = 10;

    @GetMapping("/api/applications/{applicationId}/document-requests")
    public ResponseEntity<List<DocumentRequestDto>> list(
            Authentication authentication,
            @PathVariable Long applicationId,
            @RequestParam(value = "status", required = false) DocumentRequestStatus status) {
        Long userSeq = (Long) authentication.getPrincipal();
        String role = authentication.getAuthorities().iterator().next().getAuthority();
        return ResponseEntity.ok(documentRequestService.listForApplication(userSeq, role, applicationId, status));
    }

    @Auditable(
            action = AuditAction.DOCUMENT_UPLOADED_VOLUNTARY,
            category = AuditCategory.APPLICATION,
            entityType = "DocumentRequest")
    @PostMapping("/api/applications/{applicationId}/documents")
    public ResponseEntity<VoluntaryUploadResponse> uploadVoluntary(
            Authentication authentication,
            @PathVariable Long applicationId,
            @RequestParam("documentTypeCode") String documentTypeCode,
            @RequestParam(value = "customLabel", required = false) String customLabel,
            @RequestParam("file") MultipartFile file) {
        Long userSeq = (Long) authentication.getPrincipal();
        String role = authentication.getAuthorities().iterator().next().getAuthority();
        rateLimiter.checkAndRecord(RATE_TYPE, String.valueOf(userSeq), UPLOAD_MAX, UPLOAD_WINDOW_MIN);
        log.info("Voluntary document upload: userSeq={}, applicationSeq={}, code={}, name={}",
                userSeq, applicationId, documentTypeCode, file.getOriginalFilename());
        VoluntaryUploadResponse response = documentRequestService.createVoluntaryUpload(
                userSeq, role, applicationId, documentTypeCode, customLabel, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Auditable(
            action = AuditAction.DOCUMENT_DELETED_VOLUNTARY,
            category = AuditCategory.APPLICATION,
            entityType = "DocumentRequest")
    @DeleteMapping("/api/applications/{applicationId}/documents/{docRequestId}")
    public ResponseEntity<Void> deleteVoluntary(
            Authentication authentication,
            @PathVariable Long applicationId,
            @PathVariable Long docRequestId) {
        Long userSeq = (Long) authentication.getPrincipal();
        String role = authentication.getAuthorities().iterator().next().getAuthority();
        documentRequestService.deleteVoluntary(userSeq, role, applicationId, docRequestId);
        return ResponseEntity.noContent().build();
    }
}

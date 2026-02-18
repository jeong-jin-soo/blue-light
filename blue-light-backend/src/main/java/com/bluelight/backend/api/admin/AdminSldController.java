package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.SldUploadedDto;
import com.bluelight.backend.api.application.dto.SldRequestResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Admin/LEW SLD 도면 관리 API 컨트롤러
 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'LEW', 'SYSTEM_ADMIN')")
public class AdminSldController {

    private final AdminSldService adminSldService;

    /**
     * Get SLD request for an application
     * GET /api/admin/applications/:id/sld-request
     */
    @GetMapping("/applications/{id}/sld-request")
    public ResponseEntity<SldRequestResponse> getAdminSldRequest(@PathVariable Long id) {
        log.info("Admin get SLD request: applicationSeq={}", id);
        SldRequestResponse response = adminSldService.getAdminSldRequest(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Mark SLD as uploaded by LEW
     * POST /api/admin/applications/:id/sld-uploaded
     */
    @PostMapping("/applications/{id}/sld-uploaded")
    public ResponseEntity<SldRequestResponse> uploadSld(
            @PathVariable Long id,
            @Valid @RequestBody SldUploadedDto request) {
        log.info("Admin/LEW SLD uploaded: applicationSeq={}, fileSeq={}", id, request.getFileSeq());
        SldRequestResponse response = adminSldService.uploadSld(id, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Confirm SLD
     * POST /api/admin/applications/:id/sld-confirm
     */
    @PostMapping("/applications/{id}/sld-confirm")
    public ResponseEntity<SldRequestResponse> confirmSld(@PathVariable Long id) {
        log.info("Admin/LEW SLD confirmed: applicationSeq={}", id);
        SldRequestResponse response = adminSldService.confirmSld(id);
        return ResponseEntity.ok(response);
    }
}

package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.SldUploadedDto;
import com.bluelight.backend.api.application.dto.SldRequestResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Admin/LEW SLD 도면 관리 API 컨트롤러
 *
 * <p>★ L-3 (보안 감사 H-2 동일 패턴) — LEW 호출 시 본인 배정 신청서로 한정.
 * 클래스 레벨 @PreAuthorize 는 역할 gate, 서비스의 ensureLewCanAccess 가 cross-tenant 차단.</p>
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
    public ResponseEntity<SldRequestResponse> getAdminSldRequest(
            Authentication authentication,
            @PathVariable Long id) {
        Long userSeq = (Long) authentication.getPrincipal();
        String role = authentication.getAuthorities().iterator().next().getAuthority();
        log.info("Admin get SLD request: applicationSeq={}, userSeq={}, role={}", id, userSeq, role);
        SldRequestResponse response = adminSldService.getAdminSldRequest(id, userSeq, role);
        return ResponseEntity.ok(response);
    }

    /**
     * Mark SLD as uploaded by LEW
     * POST /api/admin/applications/:id/sld-uploaded
     */
    @PostMapping("/applications/{id}/sld-uploaded")
    public ResponseEntity<SldRequestResponse> uploadSld(
            Authentication authentication,
            @PathVariable Long id,
            @Valid @RequestBody SldUploadedDto request) {
        Long userSeq = (Long) authentication.getPrincipal();
        String role = authentication.getAuthorities().iterator().next().getAuthority();
        log.info("Admin/LEW SLD uploaded: applicationSeq={}, fileSeq={}, userSeq={}, role={}",
                id, request.getFileSeq(), userSeq, role);
        SldRequestResponse response = adminSldService.uploadSld(id, request, userSeq, role);
        return ResponseEntity.ok(response);
    }

    /**
     * Confirm SLD
     * POST /api/admin/applications/:id/sld-confirm
     */
    @PostMapping("/applications/{id}/sld-confirm")
    public ResponseEntity<SldRequestResponse> confirmSld(
            Authentication authentication,
            @PathVariable Long id) {
        Long userSeq = (Long) authentication.getPrincipal();
        String role = authentication.getAuthorities().iterator().next().getAuthority();
        log.info("Admin/LEW SLD confirmed: applicationSeq={}, userSeq={}, role={}", id, userSeq, role);
        SldRequestResponse response = adminSldService.confirmSld(id, userSeq, role);
        return ResponseEntity.ok(response);
    }

    /**
     * Unconfirm SLD (reopen for re-upload)
     * POST /api/admin/applications/:id/sld-unconfirm
     */
    @PostMapping("/applications/{id}/sld-unconfirm")
    public ResponseEntity<SldRequestResponse> unconfirmSld(
            Authentication authentication,
            @PathVariable Long id) {
        Long userSeq = (Long) authentication.getPrincipal();
        String role = authentication.getAuthorities().iterator().next().getAuthority();
        log.info("Admin/LEW SLD unconfirmed (reopened): applicationSeq={}, userSeq={}, role={}", id, userSeq, role);
        SldRequestResponse response = adminSldService.unconfirmSld(id, userSeq, role);
        return ResponseEntity.ok(response);
    }
}

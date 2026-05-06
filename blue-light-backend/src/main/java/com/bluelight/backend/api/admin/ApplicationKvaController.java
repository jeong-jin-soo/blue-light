package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.ConfirmKvaRequest;
import com.bluelight.backend.api.admin.dto.ConfirmKvaResponse;
import com.bluelight.backend.common.exception.BusinessException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 5 PR#1 — LEW/ADMIN kVA 확정 API.
 *
 * <pre>
 *   PATCH /api/admin/applications/{id}/kva
 *     body: { selectedKva: int, note?: string }
 *     query: force=true (ADMIN 전용, 재확정 override)
 * </pre>
 *
 * <p>권한:
 * <ul>
 *   <li>{@code @PreAuthorize} 1차: ADMIN / SYSTEM_ADMIN / LEW 만 접근.</li>
 *   <li>컨트롤러 {@code force=true && !ROLE_ADMIN} 2차 거부 (B-4 — 403 {@code FORCE_REQUIRES_ADMIN}).</li>
 *   <li>서비스 3차: LEW 는 assignedLew 일치 시만 통과 (AC-A2).</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM_ADMIN', 'LEW')")
public class ApplicationKvaController {

    private final ApplicationKvaService applicationKvaService;

    @PatchMapping("/api/admin/applications/{id}/kva")
    public ResponseEntity<ConfirmKvaResponse> confirmKva(
            Authentication authentication,
            @PathVariable Long id,
            @RequestParam(name = "force", defaultValue = "false") boolean force,
            @Valid @RequestBody ConfirmKvaRequest request) {
        Long actorSeq = (Long) authentication.getPrincipal();
        String role = authentication.getAuthorities().iterator().next().getAuthority();

        // B-4: force=true 는 ADMIN/SYSTEM_ADMIN 전용.
        if (force && !isAdminLike(authentication)) {
            throw new BusinessException(
                    "force=true requires ADMIN role",
                    HttpStatus.FORBIDDEN, "FORCE_REQUIRES_ADMIN");
        }

        log.info("PATCH /kva: applicationId={}, actorSeq={}, role={}, force={}, requestedKva={}",
                id, actorSeq, role, force, request.getSelectedKva());

        ConfirmKvaResponse response =
                applicationKvaService.confirm(id, request, force, actorSeq, role);
        return ResponseEntity.ok(response);
    }

    private boolean isAdminLike(Authentication authentication) {
        for (GrantedAuthority a : authentication.getAuthorities()) {
            String r = a.getAuthority();
            if ("ROLE_ADMIN".equals(r) || "ROLE_SYSTEM_ADMIN".equals(r)) {
                return true;
            }
        }
        return false;
    }
}

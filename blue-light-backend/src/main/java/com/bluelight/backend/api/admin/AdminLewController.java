package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.AdminApplicationResponse;
import com.bluelight.backend.api.admin.dto.AssignLewRequest;
import com.bluelight.backend.api.admin.dto.LewSummaryResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin LEW 배정 API 컨트롤러 (ADMIN only)
 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminLewController {

    private final AdminLewService adminLewService;

    /**
     * Assign LEW to application
     * POST /api/admin/applications/:id/assign-lew
     */
    @PostMapping("/applications/{id}/assign-lew")
    public ResponseEntity<AdminApplicationResponse> assignLew(
            @PathVariable Long id,
            @Valid @RequestBody AssignLewRequest request) {
        log.info("Admin assign LEW: applicationSeq={}, lewSeq={}", id, request.getLewUserSeq());
        AdminApplicationResponse response = adminLewService.assignLew(id, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Unassign LEW from application
     * DELETE /api/admin/applications/:id/assign-lew
     */
    @DeleteMapping("/applications/{id}/assign-lew")
    public ResponseEntity<AdminApplicationResponse> unassignLew(@PathVariable Long id) {
        log.info("Admin unassign LEW: applicationSeq={}", id);
        AdminApplicationResponse response = adminLewService.unassignLew(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Get available LEWs for assignment
     * GET /api/admin/lews
     */
    @GetMapping("/lews")
    public ResponseEntity<List<LewSummaryResponse>> getAvailableLews(
            @RequestParam(required = false) Integer kva) {
        log.info("Get available LEWs for assignment: kva={}", kva);
        List<LewSummaryResponse> lews = adminLewService.getAvailableLews(kva);
        return ResponseEntity.ok(lews);
    }
}

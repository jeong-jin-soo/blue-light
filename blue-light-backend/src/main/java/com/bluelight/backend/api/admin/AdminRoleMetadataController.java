package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.RoleMetadataResponse;
import com.bluelight.backend.api.admin.dto.UpdateRoleMetadataRequest;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.audit.Auditable;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 역할 메타데이터 관리 — SYSTEM_ADMIN 전용.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/roles")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class AdminRoleMetadataController {

    private final AdminRoleMetadataService roleMetadataService;

    @GetMapping
    public ResponseEntity<List<RoleMetadataResponse>> getAll() {
        return ResponseEntity.ok(roleMetadataService.getAll());
    }

    @Auditable(action = AuditAction.ROLE_METADATA_UPDATED, category = AuditCategory.ADMIN, entityType = "RoleMetadata")
    @PatchMapping("/{roleCode}")
    public ResponseEntity<RoleMetadataResponse> update(
            @PathVariable String roleCode,
            @Valid @RequestBody UpdateRoleMetadataRequest request) {
        log.info("Update role metadata: roleCode={}, request={}", roleCode, request);
        return ResponseEntity.ok(roleMetadataService.update(roleCode, request));
    }
}

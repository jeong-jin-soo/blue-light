package com.bluelight.backend.api.role;

import com.bluelight.backend.api.admin.AdminRoleMetadataService;
import com.bluelight.backend.api.admin.dto.RoleMetadataResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 인증된 모든 사용자에게 역할 메타데이터를 제공 — 프론트엔드가 부팅 시 호출해
 * 드롭다운/라벨 등을 구성한다. 민감 정보 없음.
 */
@Slf4j
@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final AdminRoleMetadataService roleMetadataService;

    @GetMapping
    public ResponseEntity<List<RoleMetadataResponse>> getAll() {
        return ResponseEntity.ok(roleMetadataService.getAll());
    }
}

package com.bluelight.backend.api.breach;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 데이터 유출 통보 관리 API (SYSTEM_ADMIN 전용)
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/data-breaches")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class DataBreachController {

    private final DataBreachService dataBreachService;

    /**
     * 유출 통보 생성
     * POST /api/admin/data-breaches
     */
    @PostMapping
    public ResponseEntity<DataBreachResponse> reportBreach(
            Authentication authentication,
            @Valid @RequestBody DataBreachRequest request) {
        Long userSeq = (Long) authentication.getPrincipal();
        DataBreachResponse response = dataBreachService.reportBreach(userSeq, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 유출 통보 목록 조회
     * GET /api/admin/data-breaches?status=DETECTED&page=0&size=20
     */
    @GetMapping
    public ResponseEntity<Page<DataBreachResponse>> getBreaches(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<DataBreachResponse> result = dataBreachService.getBreaches(status, PageRequest.of(page, size));
        return ResponseEntity.ok(result);
    }

    /**
     * 유출 통보 상세 조회
     * GET /api/admin/data-breaches/{breachSeq}
     */
    @GetMapping("/{breachSeq}")
    public ResponseEntity<DataBreachResponse> getBreach(@PathVariable Long breachSeq) {
        DataBreachResponse response = dataBreachService.getBreach(breachSeq);
        return ResponseEntity.ok(response);
    }

    /**
     * PDPC 통보 완료 기록
     * PUT /api/admin/data-breaches/{breachSeq}/pdpc-notify
     */
    @PutMapping("/{breachSeq}/pdpc-notify")
    public ResponseEntity<DataBreachResponse> notifyPdpc(
            @PathVariable Long breachSeq,
            @RequestBody Map<String, String> body,
            Authentication authentication) {
        Long userSeq = (Long) authentication.getPrincipal();
        String pdpcReferenceNo = body.getOrDefault("pdpcReferenceNo", "");
        DataBreachResponse response = dataBreachService.notifyPdpc(breachSeq, pdpcReferenceNo, userSeq);
        return ResponseEntity.ok(response);
    }

    /**
     * 영향 받은 사용자 통보 완료 기록
     * PUT /api/admin/data-breaches/{breachSeq}/users-notify
     */
    @PutMapping("/{breachSeq}/users-notify")
    public ResponseEntity<DataBreachResponse> notifyUsers(
            @PathVariable Long breachSeq,
            Authentication authentication) {
        Long userSeq = (Long) authentication.getPrincipal();
        DataBreachResponse response = dataBreachService.notifyUsers(breachSeq, userSeq);
        return ResponseEntity.ok(response);
    }

    /**
     * 유출 해결 처리
     * PUT /api/admin/data-breaches/{breachSeq}/resolve
     */
    @PutMapping("/{breachSeq}/resolve")
    public ResponseEntity<DataBreachResponse> resolveBreach(
            @PathVariable Long breachSeq,
            Authentication authentication) {
        Long userSeq = (Long) authentication.getPrincipal();
        DataBreachResponse response = dataBreachService.resolveBreach(breachSeq, userSeq);
        return ResponseEntity.ok(response);
    }
}

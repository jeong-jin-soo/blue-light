package com.bluelight.backend.api.loa;

import com.bluelight.backend.api.file.dto.FileResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * LOA (Letter of Appointment) API 컨트롤러
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class LoaController {

    private final LoaService loaService;

    /**
     * LOA PDF 생성 (Admin/LEW 액션)
     * POST /api/admin/applications/{id}/loa/generate
     */
    @PostMapping("/api/admin/applications/{id}/loa/generate")
    public ResponseEntity<FileResponse> generateLoa(@PathVariable Long id) {
        log.info("Generate LOA: applicationSeq={}", id);
        FileResponse response = loaService.generateLoa(id);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * LOA 전자서명 (Applicant 액션)
     * POST /api/applications/{id}/loa/sign
     */
    @PostMapping("/api/applications/{id}/loa/sign")
    public ResponseEntity<FileResponse> signLoa(
            Authentication authentication,
            @PathVariable Long id,
            @RequestParam("signature") MultipartFile signatureImage) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Sign LOA: userSeq={}, applicationSeq={}", userSeq, id);
        FileResponse response = loaService.signLoa(userSeq, id, signatureImage);
        return ResponseEntity.ok(response);
    }

    /**
     * LOA 상태 조회
     * GET /api/applications/{id}/loa/status
     */
    @GetMapping("/api/applications/{id}/loa/status")
    public ResponseEntity<LoaStatusResponse> getLoaStatus(
            Authentication authentication,
            @PathVariable Long id) {
        Long userSeq = (Long) authentication.getPrincipal();
        String role = authentication.getAuthorities().iterator().next().getAuthority();
        log.info("Get LOA status: userSeq={}, applicationSeq={}", userSeq, id);
        LoaStatusResponse response = loaService.getLoaStatus(userSeq, role, id);
        return ResponseEntity.ok(response);
    }
}

package com.bluelight.backend.api.loa;

import com.bluelight.backend.api.file.dto.FileResponse;
import com.bluelight.backend.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
     * Manager 대리 서명 업로드 (★ Kaki Concierge v1.5 Phase 1 PR#6 Stage A).
     * <p>
     * 경로 A — Concierge Manager가 신청자에게 직접 받은 서명 파일을 대신 업로드한다.
     * PRD v1.5 §7.2.1-LOA / AC-15b / AC-22b (7일 이의 제기 창구) 참조.
     * <p>
     * URL이 {@code /api/admin/**}이지만 {@code @PreAuthorize}로 LEW를 명시적으로 차단한다
     * (SecurityConfig의 URL 매처는 ADMIN/LEW/SYSTEM_ADMIN 허용이므로 이중 방어).
     * <p>
     * 요청 본문(multipart):
     * <ul>
     *   <li>{@code signature} — PNG/JPEG, 최대 2MB</li>
     *   <li>{@code memo} (선택) — 수령 경로 메모 (예: "applicant emailed PDF on 2026-04-19")</li>
     *   <li>{@code acknowledgeReceipt} — true 필수, false 시 400 ACKNOWLEDGEMENT_REQUIRED</li>
     * </ul>
     *
     * POST /api/admin/applications/{id}/loa/upload-signature
     */
    @PostMapping(value = "/api/admin/applications/{id}/loa/upload-signature",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('CONCIERGE_MANAGER', 'ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<FileResponse> uploadLoaSignature(
            @PathVariable("id") Long applicationSeq,
            @RequestPart("signature") MultipartFile signature,
            @RequestParam(value = "memo", required = false) String memo,
            @RequestParam("acknowledgeReceipt") boolean acknowledgeReceipt,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        Long managerSeq = (Long) authentication.getPrincipal();
        log.info("Manager upload LOA signature: applicationSeq={}, managerSeq={}",
                applicationSeq, managerSeq);

        if (!acknowledgeReceipt) {
            throw new BusinessException(
                    "You must acknowledge that you received this signature from the applicant.",
                    HttpStatus.BAD_REQUEST, "ACKNOWLEDGEMENT_REQUIRED");
        }

        FileResponse response = loaService.uploadSignatureByManager(
                managerSeq, applicationSeq, signature, memo, httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
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

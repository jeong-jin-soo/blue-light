package com.bluelight.backend.api.invoice;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * E-Invoice API Controller.
 *
 * <p>PDF 바이너리는 기존 {@code GET /api/files/{fileId}/download} 로 내려받고,
 * 본 컨트롤러는 <b>메타데이터 + 권한 검증 + 재생성 트리거</b>만 담당한다.</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService invoiceService;

    /**
     * 신청자 본인 — Application의 Invoice 메타데이터 조회.
     * PAID 이후에만 응답, 본인 검증 실패 시 403. LEW는 이 경로로 접근할 수 없다
     * (spec §4 — 영수증은 신청자에게 귀속, LEW는 /api/admin 경로 사용).
     */
    @GetMapping("/api/applications/{id}/invoice")
    @PreAuthorize("!hasRole('LEW')")
    public ResponseEntity<InvoiceResponse> getMyInvoice(
            Authentication authentication,
            @PathVariable Long id) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Get my invoice: userSeq={}, applicationSeq={}", userSeq, id);
        return ResponseEntity.ok(invoiceService.getByApplicationForApplicant(id, userSeq));
    }

    /**
     * Admin/SYSTEM_ADMIN — Application Invoice 조회.
     */
    @GetMapping("/api/admin/applications/{id}/invoice")
    @PreAuthorize("hasAnyRole('ADMIN','SYSTEM_ADMIN')")
    public ResponseEntity<InvoiceResponse> getInvoiceAsAdmin(
            Authentication authentication,
            @PathVariable Long id) {
        Long adminSeq = (Long) authentication.getPrincipal();
        log.info("Get invoice (admin): adminSeq={}, applicationSeq={}", adminSeq, id);
        return ResponseEntity.ok(invoiceService.getByApplicationForAdmin(id, adminSeq));
    }

    /**
     * Admin/SYSTEM_ADMIN — Invoice PDF 재생성. 스냅샷 불변, PDF 파일만 교체.
     * 재생성 사유 필수(감사 로그에 기록).
     */
    @PostMapping("/api/admin/applications/{id}/invoice/regenerate")
    @PreAuthorize("hasAnyRole('ADMIN','SYSTEM_ADMIN')")
    public ResponseEntity<InvoiceResponse> regenerate(
            Authentication authentication,
            @PathVariable Long id,
            @Valid @RequestBody RegenerateRequest request) {
        Long adminSeq = (Long) authentication.getPrincipal();
        log.info("Regenerate invoice: adminSeq={}, applicationSeq={}, reason={}",
                adminSeq, id, request.getReason());
        return ResponseEntity.ok(
                invoiceService.regenerate(id, adminSeq, request.getReason()));
    }

    @Getter
    @NoArgsConstructor
    public static class RegenerateRequest {
        @NotBlank(message = "Regeneration reason is required")
        @Size(max = 500, message = "Reason must be 500 characters or less")
        private String reason;
    }
}

package com.bluelight.backend.api.loa;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * LoA 교환 모델 컨트롤러 (loa-exchange-redesign-spec.md §3.2 / §3.3, PR3b).
 *
 * <p>기존 {@link LoaController}(generate/sign — 디지털 서명 모델)와 분리. 본 컨트롤러는
 * "admin 관리 폼 다운로드 → 신청자 오프라인 서명본 업로드 → LEW 최종본 업로드" 교환 흐름을 담당한다.</p>
 *
 * <ul>
 *   <li>{@code /api/lew/**} — SecurityConfig URL 매처가 LEW 로 제한 + 메서드별 {@code @appSec.isAssignedLew} 로 배정 검증.</li>
 *   <li>{@code /api/applications/**} — anyRequest authenticated + 서비스 레이어 {@code OwnershipValidator} 로 소유자 검증.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class LoaExchangeController {

    private final LoaService loaService;

    // ── §3.3 LEW: 폼 전달 (NEW 전용) ──

    /**
     * POST /api/lew/applications/{id}/loa/send-form — 담당 LEW 가 active 폼을 신청자에게 전달.
     * <p>409: {@code NO_ACTIVE_LOA_FORM}(active 폼 부재), {@code LOA_FORM_NOT_APPLICABLE}(RENEWAL).</p>
     */
    @PostMapping("/api/lew/applications/{id}/loa/send-form")
    @PreAuthorize("@appSec.isAssignedLew(#id, authentication)")
    public ResponseEntity<LoaStatusResponse> sendForm(
            @PathVariable("id") Long id,
            Authentication authentication) {
        Long lewUserSeq = (Long) authentication.getPrincipal();
        log.info("LEW sendLoaForm: lewUserSeq={}, applicationSeq={}", lewUserSeq, id);
        return ResponseEntity.ok(loaService.sendLoaForm(lewUserSeq, id));
    }

    // ── §3.3 Owner: 서명본 업로드 ──

    /**
     * POST /api/applications/{id}/loa/applicant-upload — 신청자(또는 ADMIN 대리)가 오프라인 서명본 업로드.
     * <p>multipart {@code file}(PDF/JPG/PNG, ≤20MB). 소유자 검증은 서비스 레이어 {@code OwnershipValidator}.</p>
     */
    @PostMapping(value = "/api/applications/{id}/loa/applicant-upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<LoaStatusResponse> applicantUpload(
            @PathVariable("id") Long id,
            @RequestPart("file") MultipartFile file,
            Authentication authentication) {
        Long userSeq = (Long) authentication.getPrincipal();
        String role = authentication.getAuthorities().iterator().next().getAuthority();
        log.info("Applicant uploadLoa: userSeq={}, applicationSeq={}", userSeq, id);
        return ResponseEntity.ok(loaService.applicantUploadLoa(userSeq, role, id, file));
    }

    // ── §3.3 LEW: 최종본 업로드 ──

    /**
     * POST /api/lew/applications/{id}/loa/final-upload — 담당 LEW 가 보완한 최종본 업로드.
     * <p>multipart {@code file}(PDF/JPG/PNG, ≤20MB).</p>
     */
    @PostMapping(value = "/api/lew/applications/{id}/loa/final-upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@appSec.isAssignedLew(#id, authentication)")
    public ResponseEntity<LoaStatusResponse> finalUpload(
            @PathVariable("id") Long id,
            @RequestPart("file") MultipartFile file,
            Authentication authentication) {
        Long lewUserSeq = (Long) authentication.getPrincipal();
        log.info("LEW finalUploadLoa: lewUserSeq={}, applicationSeq={}", lewUserSeq, id);
        return ResponseEntity.ok(loaService.finalUploadLoa(lewUserSeq, id, file));
    }

    // §3.2 active 폼 메타/다운로드(/api/applications/{id}/loa/active-form[/download])는
    // PR2 LoaActiveFormController 가 단독 담당 — 여기서 중복 매핑하지 않는다.
}

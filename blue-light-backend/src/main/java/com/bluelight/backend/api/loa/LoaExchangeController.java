package com.bluelight.backend.api.loa;

import com.bluelight.backend.common.util.EnumParser;
import com.bluelight.backend.domain.file.FileType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    // ── Part B ADMIN: LoA 파일 등록/교체 (기존 파일 보관 + 사유 기록) ──

    /**
     * POST /api/admin/applications/{id}/loa/admin-replace — ADMIN/SYSTEM_ADMIN 이 LoA 파일을 등록/교체.
     *
     * <p>multipart {@code file}(PDF/JPG/PNG, ≤20MB) + {@code fileType}(OWNER_AUTH_LETTER|LOA_FINAL)
     * + {@code reason}(필수). 기존 동일 타입 파일은 삭제하지 않고 보관하며, 사유는 감사 로그에 남는다.</p>
     * <p>400: {@code INVALID_LOA_FILE_TYPE}(허용되지 않는 타입), {@code LOA_REASON_REQUIRED}(사유 누락).</p>
     */
    @PostMapping(value = "/api/admin/applications/{id}/loa/admin-replace",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','SYSTEM_ADMIN')")
    public ResponseEntity<LoaStatusResponse> adminReplace(
            @PathVariable("id") Long id,
            @RequestParam("fileType") String fileType,
            @RequestPart("file") MultipartFile file,
            @RequestParam("reason") String reason,
            Authentication authentication) {
        Long adminSeq = (Long) authentication.getPrincipal();
        FileType parsedType = EnumParser.parse(FileType.class, fileType, "INVALID_LOA_FILE_TYPE");
        log.info("ADMIN adminReplaceLoa: adminSeq={}, applicationSeq={}, fileType={}",
                adminSeq, id, parsedType);
        return ResponseEntity.ok(loaService.adminReplaceLoa(adminSeq, id, parsedType, file, reason));
    }

    /**
     * POST /api/admin/applications/{id}/loa/send-form — ADMIN/SYSTEM_ADMIN 이 신청자에게 active LoA 폼 전달.
     *
     * <p>LEW 전용 {@code /api/lew/.../send-form}(URL 매처가 LEW 한정)과 동일 동작이지만,
     * 배정 LEW가 없거나 ADMIN 이 직접 진행해야 하는 경우를 위해 {@code /api/admin/**} 경로로 제공.
     * 409: {@code NO_ACTIVE_LOA_FORM}(active 폼 부재), {@code LOA_FORM_NOT_APPLICABLE}(RENEWAL).</p>
     */
    @PostMapping("/api/admin/applications/{id}/loa/send-form")
    @PreAuthorize("hasAnyRole('ADMIN','SYSTEM_ADMIN')")
    public ResponseEntity<LoaStatusResponse> adminSendForm(
            @PathVariable("id") Long id,
            Authentication authentication) {
        Long adminSeq = (Long) authentication.getPrincipal();
        log.info("ADMIN sendLoaForm: adminSeq={}, applicationSeq={}", adminSeq, id);
        return ResponseEntity.ok(loaService.sendLoaForm(adminSeq, id));
    }

    // §3.2 active 폼 메타/다운로드(/api/applications/{id}/loa/active-form[/download])는
    // PR2 LoaActiveFormController 가 단독 담당 — 여기서 중복 매핑하지 않는다.
}

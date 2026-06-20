package com.bluelight.backend.api.sld;

import com.bluelight.backend.api.sld.dto.SldConversionResponse;
import com.bluelight.backend.domain.kva.ChangedByRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SLD self-upload → LEW 작성 전환(E2) 엔드포인트.
 *
 * <p>spec: {@code doc/Project Analysis/sld-lew-conversion-fee-spec.md} §9.
 * 신청자가 직접 제출하기로 했으나 미제공/무효일 때 LEW(또는 ADMIN)가 작성을 떠맡고 SLD 작성비를 청구한다.
 * 결제 후 전환이면 보충 청구 정산 원장(SLD_ADDED, PENDING)이 기록된다.</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class SldConversionController {

    private final SldConversionService sldConversionService;

    /** 담당 LEW 가 SLD 작성으로 전환. */
    @PostMapping("/lew/applications/{id}/sld/convert-to-lew")
    @PreAuthorize("@appSec.isAssignedLew(#id, authentication)")
    public ResponseEntity<SldConversionResponse> convertByLew(
            @PathVariable("id") Long id,
            Authentication authentication) {
        Long actorSeq = (Long) authentication.getPrincipal();
        log.info("LEW SLD convert-to-lew: actorSeq={}, applicationSeq={}", actorSeq, id);
        return ResponseEntity.ok(
                sldConversionService.convertToLewCreated(id, actorSeq, ChangedByRole.LEW));
    }

    /** ADMIN/SYSTEM_ADMIN 이 SLD 작성으로 전환 (대리). */
    @PostMapping("/admin/applications/{id}/sld/convert-to-lew")
    @PreAuthorize("hasAnyRole('ADMIN','SYSTEM_ADMIN')")
    public ResponseEntity<SldConversionResponse> convertByAdmin(
            @PathVariable("id") Long id,
            Authentication authentication) {
        Long actorSeq = (Long) authentication.getPrincipal();
        log.info("ADMIN SLD convert-to-lew: actorSeq={}, applicationSeq={}", actorSeq, id);
        return ResponseEntity.ok(
                sldConversionService.convertToLewCreated(id, actorSeq, ChangedByRole.ADMIN));
    }
}

package com.bluelight.backend.api.loa;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * LOA (Letter of Appointment) API 컨트롤러
 * <p>
 * 레거시 generate/sign/upload-signature 모델은 교환(exchange) 모델로 대체되어 제거됨.
 * 파일 교환 엔드포인트는 {@code LoaExchangeController} 가 담당하고,
 * 여기서는 상태 조회만 제공한다.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class LoaController {

    private final LoaService loaService;

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

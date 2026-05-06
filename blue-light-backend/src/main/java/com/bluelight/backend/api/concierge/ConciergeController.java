package com.bluelight.backend.api.concierge;

import com.bluelight.backend.api.concierge.dto.ConciergeRequestCreateRequest;
import com.bluelight.backend.api.concierge.dto.ConciergeRequestCreateResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public Concierge 신청 엔드포인트 (★ Kaki Concierge v1.5, Phase 1 PR#2 Stage B).
 * <p>
 * 권한: 인증 불필요. SecurityConfig의 {@code /api/public/**} 매처로 permitAll 적용됨.
 * Rate-limit은 Stage C에서 GenericRateLimiter 연결 예정 (§4.4 M-1).
 */
@RestController
@RequestMapping("/api/public/concierge")
@RequiredArgsConstructor
public class ConciergeController {

    private final ConciergeService conciergeService;

    /**
     * 통합 신청 + 자동 가입 트랜잭션 엔드포인트 (PRD §4 / v1.3 통합 플로우).
     *
     * @return 201 CREATED + ConciergeRequestCreateResponse
     *         - 400: Validation 실패 (필수 동의 미체크, 이메일 포맷 등)
     *         - 409: ACCOUNT_NOT_ELIGIBLE (C4 — SUSPENDED/DELETED)
     *         - 422: STAFF_EMAIL_NOT_ALLOWED (C5 — 스태프 계정)
     */
    @PostMapping("/request")
    public ResponseEntity<ConciergeRequestCreateResponse> submit(
        @Valid @RequestBody ConciergeRequestCreateRequest request,
        HttpServletRequest httpRequest) {
        ConciergeRequestCreateResponse resp = conciergeService.submitRequest(request, httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }
}

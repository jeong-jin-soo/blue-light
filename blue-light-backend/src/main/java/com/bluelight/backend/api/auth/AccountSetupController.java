package com.bluelight.backend.api.auth;

import com.bluelight.backend.api.auth.dto.AccountSetupCompleteRequest;
import com.bluelight.backend.api.auth.dto.AccountSetupStatusResponse;
import com.bluelight.backend.api.auth.dto.TokenResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public Account Setup 엔드포인트 (★ Kaki Concierge v1.5, Phase 1 PR#2 Stage A).
 * <p>
 * 컨시어지 자동 가입 계정 또는 PENDING_ACTIVATION 계정이 활성화 링크로 진입하는 공개 경로.
 * 토큰 UUID 기반 인증만 수행 (로그인 불필요). SecurityConfig의 {@code /api/public/**} 매처로 permitAll 적용됨.
 * URL path 토큰은 {@code TokenLogMaskingFilter}(Stage 3)에서 access log/MDC 마스킹 처리됨.
 */
@RestController
@RequestMapping("/api/public/account-setup")
@RequiredArgsConstructor
public class AccountSetupController {

    private final AccountSetupService accountSetupService;

    /**
     * 토큰 상태 조회 (Setup 페이지 진입 직전).
     * 토큰이 유효하지 않으면 410 GONE.
     */
    @GetMapping("/{token}")
    public ResponseEntity<AccountSetupStatusResponse> getStatus(@PathVariable("token") String token) {
        return ResponseEntity.ok(accountSetupService.getStatus(token));
    }

    /**
     * 비밀번호 설정 완료. 성공 시 자동 로그인 JWT 발급.
     * <p>
     * 실패 응답:
     * <ul>
     *   <li>400 PASSWORD_MISMATCH — password ≠ passwordConfirm</li>
     *   <li>400 PASSWORD_POLICY_VIOLATION — 정책 위반</li>
     *   <li>410 TOKEN_INVALID / TOKEN_ALREADY_USED / TOKEN_LOCKED / TOKEN_REVOKED / TOKEN_EXPIRED</li>
     * </ul>
     */
    @PostMapping("/{token}")
    public ResponseEntity<TokenResponse> complete(
        @PathVariable("token") String token,
        @Valid @RequestBody AccountSetupCompleteRequest request,
        HttpServletRequest httpRequest) {
        return ResponseEntity.ok(accountSetupService.complete(token, request, httpRequest));
    }
}

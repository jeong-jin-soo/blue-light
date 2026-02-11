package com.bluelight.backend.api.auth;

import com.bluelight.backend.api.auth.dto.ForgotPasswordRequest;
import com.bluelight.backend.api.auth.dto.LoginRequest;
import com.bluelight.backend.api.auth.dto.ResetPasswordRequest;
import com.bluelight.backend.api.auth.dto.SignupRequest;
import com.bluelight.backend.api.auth.dto.TokenResponse;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.security.LoginRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

/**
 * 인증 API 컨트롤러
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final LoginRateLimiter loginRateLimiter;

    /**
     * 가입 가능한 역할 목록 조회 (Public)
     * GET /api/auth/signup-options
     */
    @GetMapping("/signup-options")
    public ResponseEntity<Map<String, Object>> getSignupOptions() {
        log.info("회원가입 옵션 조회");
        Map<String, Object> options = authService.getSignupOptions();
        return ResponseEntity.ok(options);
    }

    /**
     * 회원가입
     * POST /api/auth/signup
     */
    @PostMapping("/signup")
    public ResponseEntity<TokenResponse> signup(@Valid @RequestBody SignupRequest request) {
        log.info("회원가입 요청: email={}", request.getEmail());
        TokenResponse response = authService.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 로그인 (Rate Limiting 적용: IP당 15분 내 최대 5회)
     * POST /api/auth/login
     */
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {

        String clientIp = httpRequest.getRemoteAddr();

        if (loginRateLimiter.isBlocked(clientIp)) {
            log.warn("Rate limit exceeded for IP: {}", clientIp);
            throw new BusinessException(
                    "Too many login attempts. Please try again later.",
                    HttpStatus.TOO_MANY_REQUESTS,
                    "RATE_LIMIT_EXCEEDED"
            );
        }

        log.info("로그인 요청: email={}", request.getEmail());

        try {
            TokenResponse response = authService.login(request);
            loginRateLimiter.clearAttempts(clientIp);
            return ResponseEntity.ok(response);
        } catch (BusinessException e) {
            loginRateLimiter.recordFailedAttempt(clientIp);
            throw e;
        }
    }

    /**
     * 비밀번호 재설정 요청 (이메일 발송)
     * POST /api/auth/forgot-password
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request) {
        log.info("비밀번호 재설정 요청: email={}", request.getEmail());
        authService.forgotPassword(request);
        // 보안: 이메일 존재 여부와 관계없이 동일한 응답
        return ResponseEntity.ok(Map.of(
                "message", "If an account with that email exists, a password reset link has been sent."
        ));
    }

    /**
     * 비밀번호 재설정 실행
     * POST /api/auth/reset-password
     */
    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request) {
        log.info("비밀번호 재설정 실행");
        authService.resetPassword(request);
        return ResponseEntity.ok(Map.of(
                "message", "Your password has been reset successfully."
        ));
    }

    /**
     * 이메일 인증 처리 (Public - 토큰 기반)
     * GET /api/auth/verify-email?token=xxx
     */
    @GetMapping("/verify-email")
    public ResponseEntity<Map<String, String>> verifyEmail(@RequestParam String token) {
        log.info("이메일 인증 요청: token={}", token.substring(0, Math.min(8, token.length())) + "...");
        authService.verifyEmail(token);
        return ResponseEntity.ok(Map.of(
                "message", "Your email has been verified successfully."
        ));
    }

    /**
     * 인증 이메일 재발송 (인증된 사용자만)
     * POST /api/auth/resend-verification
     */
    @PostMapping("/resend-verification")
    public ResponseEntity<Map<String, String>> resendVerification() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null || !(auth.getPrincipal() instanceof Long)) {
            throw new BusinessException("Authentication required", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        Long userSeq = (Long) auth.getPrincipal();
        log.info("인증 이메일 재발송 요청: userSeq={}", userSeq);
        authService.resendVerificationEmail(userSeq);
        return ResponseEntity.ok(Map.of(
                "message", "Verification email has been sent."
        ));
    }
}

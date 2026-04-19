package com.bluelight.backend.api.auth;

import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.api.auth.dto.ActivationLinkRequest;
import com.bluelight.backend.api.auth.dto.ActivationLinkResponse;
import com.bluelight.backend.api.auth.dto.ForgotPasswordRequest;
import com.bluelight.backend.api.auth.dto.LoginRequest;
import com.bluelight.backend.api.auth.dto.ResetPasswordRequest;
import com.bluelight.backend.api.auth.dto.SignupRequest;
import com.bluelight.backend.api.auth.dto.TokenResponse;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.security.JwtTokenProvider;
import com.bluelight.backend.security.LoginRateLimiter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
    private final LoginActivationService loginActivationService;
    private final LoginRateLimiter loginRateLimiter;
    private final AuditLogService auditLogService;
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * 활성화 링크 요청 시 5케이스 공통 고정 메시지 (★ v1.5 §4.4 이메일 유출 방지).
     */
    private static final String ACTIVATION_FIXED_MESSAGE =
        "If this email is registered and eligible for activation, we've sent an activation link.";

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
    public ResponseEntity<TokenResponse> signup(
            @Valid @RequestBody SignupRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        log.info("회원가입 요청: email={}", request.getEmail());
        TokenResponse response = authService.signup(request);
        addJwtCookie(httpResponse, response.getAccessToken());
        auditLogService.log(
                response.getUserSeq(), request.getEmail(), response.getRole(),
                AuditAction.SIGNUP, AuditCategory.AUTH,
                "User", String.valueOf(response.getUserSeq()),
                "회원가입: " + request.getEmail(),
                null, null,
                getClientIp(httpRequest), httpRequest.getHeader("User-Agent"),
                "POST", "/api/auth/signup", 201);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 로그인 (Rate Limiting 적용: IP당 15분 내 최대 5회).
     * <p>
     * ★ Kaki Concierge v1.5 H-1: 감사 로그(LOGIN_SUCCESS/LOGIN_FAILED_*)는
     * 서비스 레이어({@link AuthService#login})에서 상황별로 세분화하여 기록한다.
     * Controller는 Rate limit과 JWT 쿠키 세팅만 담당.
     *
     * POST /api/auth/login
     */
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

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
            TokenResponse response = authService.login(request, httpRequest);
            loginRateLimiter.clearAttempts(clientIp);
            addJwtCookie(httpResponse, response.getAccessToken());
            return ResponseEntity.ok(response);
        } catch (BusinessException e) {
            loginRateLimiter.recordFailedAttempt(clientIp);
            // 감사 로그는 서비스 레이어에서 세분화 기록함 (LOGIN_FAILED_UNKNOWN_EMAIL / BAD_PASSWORD / DELETED)
            throw e;
        }
    }

    /**
     * 활성화 링크 재발송 요청 (★ Kaki Concierge v1.5 §4.4 옵션 B).
     * <p>
     * PENDING_ACTIVATION 계정의 AccountSetupToken을 재발급하여 이메일로 발송한다.
     * 이메일 존재 여부를 노출하지 않기 위해 <b>모든 케이스에서 동일한 200 + 고정 메시지</b>.
     * Rate limit은 IP 기반(LoginRateLimiter 재사용) — 동일 IP에서 과도한 요청 차단.
     *
     * POST /api/auth/login/request-activation
     */
    @PostMapping("/login/request-activation")
    public ResponseEntity<ActivationLinkResponse> requestActivation(
            @Valid @RequestBody ActivationLinkRequest request,
            HttpServletRequest httpRequest) {

        String clientIp = httpRequest.getRemoteAddr();

        // Rate limit — LoginRateLimiter 재사용 (로그인/활성화 공통 IP 카운트)
        if (loginRateLimiter.isBlocked(clientIp)) {
            log.warn("Activation link rate limit exceeded for IP: {}", clientIp);
            throw new BusinessException(
                    "Too many activation requests. Please try again later.",
                    HttpStatus.TOO_MANY_REQUESTS,
                    "RATE_LIMIT_EXCEEDED"
            );
        }

        // 응답은 항상 고정 메시지 — 내부 분기는 서비스가 처리
        loginActivationService.requestActivation(request.getEmail(), httpRequest);

        // 과도한 스캐닝 차단용 — 성공/미매칭 관계없이 IP 카운트 증가
        loginRateLimiter.recordFailedAttempt(clientIp);

        return ResponseEntity.ok(ActivationLinkResponse.builder()
                .message(ACTIVATION_FIXED_MESSAGE)
                .build());
    }

    /**
     * 비밀번호 재설정 요청 (이메일 발송)
     * POST /api/auth/forgot-password
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request,
            HttpServletRequest httpRequest) {
        log.info("비밀번호 재설정 요청: email={}", request.getEmail());
        authService.forgotPassword(request);
        auditLogService.log(
                null, request.getEmail(), null,
                AuditAction.PASSWORD_RESET_REQUEST, AuditCategory.AUTH,
                null, null,
                "비밀번호 재설정 요청: " + request.getEmail(),
                null, null,
                getClientIp(httpRequest), httpRequest.getHeader("User-Agent"),
                "POST", "/api/auth/forgot-password", 200);
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
            @Valid @RequestBody ResetPasswordRequest request,
            HttpServletRequest httpRequest) {
        log.info("비밀번호 재설정 실행");
        authService.resetPassword(request);
        auditLogService.log(
                null, null, null,
                AuditAction.PASSWORD_RESET_COMPLETE, AuditCategory.AUTH,
                null, null,
                "비밀번호 재설정 완료",
                null, null,
                getClientIp(httpRequest), httpRequest.getHeader("User-Agent"),
                "POST", "/api/auth/reset-password", 200);
        return ResponseEntity.ok(Map.of(
                "message", "Your password has been reset successfully."
        ));
    }

    /**
     * 이메일 인증 처리 (Public - 토큰 기반)
     * GET /api/auth/verify-email?token=xxx
     */
    @GetMapping("/verify-email")
    public ResponseEntity<Map<String, String>> verifyEmail(
            @RequestParam String token,
            HttpServletRequest httpRequest) {
        log.info("이메일 인증 요청: token={}", token.substring(0, Math.min(8, token.length())) + "...");
        authService.verifyEmail(token);
        auditLogService.log(
                null, null, null,
                AuditAction.EMAIL_VERIFIED, AuditCategory.AUTH,
                null, null,
                "이메일 인증 완료",
                null, null,
                getClientIp(httpRequest), httpRequest.getHeader("User-Agent"),
                "GET", "/api/auth/verify-email", 200);
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

    /**
     * 로그아웃 (httpOnly 쿠키 삭제)
     * POST /api/auth/logout
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(HttpServletResponse httpResponse) {
        clearJwtCookie(httpResponse);
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    /**
     * JWT 토큰을 httpOnly 쿠키에 설정
     */
    private void addJwtCookie(HttpServletResponse response, String token) {
        Cookie cookie = new Cookie("bluelight_token", token);
        cookie.setHttpOnly(true);
        cookie.setSecure(false); // 개발환경: false, 운영환경: true
        cookie.setPath("/");
        cookie.setMaxAge(jwtTokenProvider.getExpirationInSeconds().intValue());
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    /**
     * JWT 쿠키 삭제 (로그아웃 시)
     */
    private void clearJwtCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie("bluelight_token", "");
        cookie.setHttpOnly(true);
        cookie.setSecure(false);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

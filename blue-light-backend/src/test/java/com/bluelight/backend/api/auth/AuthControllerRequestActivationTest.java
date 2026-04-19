package com.bluelight.backend.api.auth;

import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.api.auth.dto.ActivationLinkRequest;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.security.JwtTokenProvider;
import com.bluelight.backend.security.LoginRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;
import tools.jackson.databind.ObjectMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@code POST /api/auth/login/request-activation} 웹 레이어 테스트 (★ v1.5 Stage C).
 * Standalone MockMvc (Spring 컨텍스트/DB 의존 없음).
 */
@DisplayName("AuthController.requestActivation - PR#2 Stage C")
class AuthControllerRequestActivationTest {

    private AuthService authService;
    private LoginActivationService loginActivationService;
    private LoginRateLimiter loginRateLimiter;
    private AuditLogService auditLogService;
    private JwtTokenProvider jwtTokenProvider;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private HandlerExceptionResolver globalResolver() {
        return new ExceptionHandlerExceptionResolver() {
            @Override
            public org.springframework.web.servlet.ModelAndView resolveException(
                HttpServletRequest request, HttpServletResponse response,
                Object handler, Exception ex) {
                if (ex instanceof BusinessException be) {
                    response.setStatus(be.getStatus().value());
                    return new org.springframework.web.servlet.ModelAndView();
                }
                if (ex instanceof org.springframework.web.bind.MethodArgumentNotValidException) {
                    response.setStatus(HttpStatus.BAD_REQUEST.value());
                    return new org.springframework.web.servlet.ModelAndView();
                }
                return null;
            }
        };
    }

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        loginActivationService = mock(LoginActivationService.class);
        loginRateLimiter = mock(LoginRateLimiter.class);
        auditLogService = mock(AuditLogService.class);
        jwtTokenProvider = mock(JwtTokenProvider.class);
        objectMapper = new ObjectMapper();

        AuthController controller = new AuthController(
            authService, loginActivationService, loginRateLimiter, auditLogService, jwtTokenProvider);
        mockMvc = MockMvcBuilders
            .standaloneSetup(controller)
            .setHandlerExceptionResolvers(globalResolver())
            .build();
    }

    private String body(String email) throws Exception {
        ActivationLinkRequest req = new ActivationLinkRequest();
        req.setEmail(email);
        return objectMapper.writeValueAsString(req);
    }

    // ============================================================
    // 정상 경로 — 고정 응답
    // ============================================================

    @Test
    @DisplayName("POST - 정상 요청이면 200 + 고정 메시지 (5케이스 모두 동일 응답 정책)")
    void requestActivation_success() throws Exception {
        when(loginRateLimiter.isBlocked(anyString())).thenReturn(false);

        mockMvc.perform(post("/api/auth/login/request-activation")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("anyone@example.com")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message")
                .value("If this email is registered and eligible for activation, we've sent an activation link."));

        // 서비스 호출 + IP 카운트 증가
        verify(loginActivationService).requestActivation(eq("anyone@example.com"), any());
        verify(loginRateLimiter).recordFailedAttempt(anyString());
    }

    // ============================================================
    // Validation 실패
    // ============================================================

    @Test
    @DisplayName("POST - 이메일 형식 오류 → 400 (Validation)")
    void requestActivation_invalidEmail_returns400() throws Exception {
        when(loginRateLimiter.isBlocked(anyString())).thenReturn(false);

        mockMvc.perform(post("/api/auth/login/request-activation")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("not-an-email")))
            .andExpect(status().isBadRequest());

        verify(loginActivationService, never()).requestActivation(anyString(), any());
    }

    @Test
    @DisplayName("POST - 이메일 누락 → 400")
    void requestActivation_missingEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/login/request-activation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
    }

    // ============================================================
    // Rate limit
    // ============================================================

    @Test
    @DisplayName("POST - Rate limit 초과 → 429 TOO_MANY_REQUESTS")
    void requestActivation_rateLimited_returns429() throws Exception {
        when(loginRateLimiter.isBlocked(anyString())).thenReturn(true);

        mockMvc.perform(post("/api/auth/login/request-activation")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("x@y.com")))
            .andExpect(status().isTooManyRequests());

        verify(loginActivationService, never()).requestActivation(anyString(), any());
    }
}

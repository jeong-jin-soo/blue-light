package com.bluelight.backend.api.auth;

import com.bluelight.backend.api.auth.dto.AccountSetupCompleteRequest;
import com.bluelight.backend.api.auth.dto.AccountSetupStatusResponse;
import com.bluelight.backend.api.auth.dto.TokenResponse;
import com.bluelight.backend.common.exception.BusinessException;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AccountSetupController 웹 레이어 테스트 (★ Kaki Concierge v1.5, Phase 1 PR#2 Stage A).
 * <p>
 * Standalone MockMvc (Spring 컨텍스트/DB 의존 없음). BusinessException → HTTP status 매핑은
 * 실제 GlobalExceptionHandler가 아닌 간이 HandlerExceptionResolver로 테스트 범위에서만 처리.
 */
@DisplayName("AccountSetupController - PR#2 Stage A")
class AccountSetupControllerTest {

    private AccountSetupService accountSetupService;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    /**
     * BusinessException → HTTP status 매핑 최소 구현 (테스트 전용).
     * 실제 운영은 GlobalExceptionHandler가 처리하지만, standalone MockMvc에는 주입되지 않으므로
     * exception resolver를 직접 제공한다.
     */
    private HandlerExceptionResolver businessExceptionResolver() {
        return new ExceptionHandlerExceptionResolver() {
            @Override
            public org.springframework.web.servlet.ModelAndView resolveException(
                HttpServletRequest request, HttpServletResponse response,
                Object handler, Exception ex) {
                if (ex instanceof BusinessException be) {
                    response.setStatus(be.getStatus().value());
                    return new org.springframework.web.servlet.ModelAndView();
                }
                return null;
            }
        };
    }

    @BeforeEach
    void setUp() {
        accountSetupService = mock(AccountSetupService.class);
        objectMapper = new ObjectMapper();
        mockMvc = MockMvcBuilders
            .standaloneSetup(new AccountSetupController(accountSetupService))
            .setHandlerExceptionResolvers(businessExceptionResolver())
            .build();
    }

    // ============================================================
    // GET /api/public/account-setup/{token}
    // ============================================================

    @Test
    @DisplayName("GET - 유효 토큰이면 200 + 마스킹된 이메일")
    void getStatus_valid_returns200() throws Exception {
        AccountSetupStatusResponse response = AccountSetupStatusResponse.builder()
            .maskedEmail("a***@example.com")
            .expiresAt(LocalDateTime.of(2026, 4, 21, 12, 0))
            .build();
        when(accountSetupService.getStatus("ok-uuid")).thenReturn(response);

        mockMvc.perform(get("/api/public/account-setup/ok-uuid"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.maskedEmail").value("a***@example.com"));
    }

    @Test
    @DisplayName("GET - 유효하지 않은 토큰이면 410 GONE")
    void getStatus_invalid_returns410() throws Exception {
        when(accountSetupService.getStatus("bad-uuid"))
            .thenThrow(new BusinessException("invalid", HttpStatus.GONE, "TOKEN_INVALID"));

        mockMvc.perform(get("/api/public/account-setup/bad-uuid"))
            .andExpect(status().isGone());
    }

    // ============================================================
    // POST /api/public/account-setup/{token}
    // ============================================================

    @Test
    @DisplayName("POST - 정상 비밀번호 설정 시 200 + JWT")
    void complete_valid_returns200() throws Exception {
        TokenResponse tokenRes = TokenResponse.of(
            "jwt-xyz", 86400L, 1L, "a@b.com", "A", "B", "APPLICANT", true, true);
        when(accountSetupService.complete(eq("ok-uuid"), any(), any())).thenReturn(tokenRes);

        AccountSetupCompleteRequest body = new AccountSetupCompleteRequest();
        body.setPassword("NewPass123");
        body.setPasswordConfirm("NewPass123");

        mockMvc.perform(post("/api/public/account-setup/ok-uuid")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").value("jwt-xyz"))
            .andExpect(jsonPath("$.userSeq").value(1));
    }

    @Test
    @DisplayName("POST - Service에서 PASSWORD_MISMATCH 던지면 400")
    void complete_mismatch_returns400() throws Exception {
        when(accountSetupService.complete(anyString(), any(), any()))
            .thenThrow(new BusinessException("mismatch", HttpStatus.BAD_REQUEST, "PASSWORD_MISMATCH"));

        AccountSetupCompleteRequest body = new AccountSetupCompleteRequest();
        body.setPassword("NewPass123");
        body.setPasswordConfirm("OtherPass123");

        mockMvc.perform(post("/api/public/account-setup/ok-uuid")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST - Service에서 TOKEN_LOCKED 던지면 410")
    void complete_tokenLocked_returns410() throws Exception {
        when(accountSetupService.complete(anyString(), any(), any()))
            .thenThrow(new BusinessException("locked", HttpStatus.GONE, "TOKEN_LOCKED"));

        AccountSetupCompleteRequest body = new AccountSetupCompleteRequest();
        body.setPassword("NewPass123");
        body.setPasswordConfirm("NewPass123");

        mockMvc.perform(post("/api/public/account-setup/ok-uuid")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isGone());
    }
}

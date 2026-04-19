package com.bluelight.backend.api.concierge;

import com.bluelight.backend.api.concierge.dto.ConciergeRequestCreateRequest;
import com.bluelight.backend.api.concierge.dto.ConciergeRequestCreateResponse;
import com.bluelight.backend.common.exception.BusinessException;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * ConciergeController 웹 레이어 테스트 (★ Kaki Concierge v1.5, Phase 1 PR#2 Stage B).
 * Standalone MockMvc (Spring 컨텍스트/DB 의존 없음).
 */
@DisplayName("ConciergeController - PR#2 Stage B")
class ConciergeControllerTest {

    private ConciergeService conciergeService;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

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
        conciergeService = mock(ConciergeService.class);
        objectMapper = new ObjectMapper();
        mockMvc = MockMvcBuilders
            .standaloneSetup(new ConciergeController(conciergeService))
            .setHandlerExceptionResolvers(businessExceptionResolver())
            .build();
    }

    private ConciergeRequestCreateRequest validBody() {
        ConciergeRequestCreateRequest req = new ConciergeRequestCreateRequest();
        req.setFullName("Tan Wei Ming");
        req.setEmail("tan@example.com");
        req.setMobileNumber("+6591234567");
        req.setMemo("memo");
        req.setPdpaConsent(true);
        req.setTermsAgreed(true);
        req.setSignupConsent(true);
        req.setDelegationConsent(true);
        req.setMarketingOptIn(false);
        return req;
    }

    @Test
    @DisplayName("POST - 정상 요청이면 201 CREATED + 응답 본문")
    void submit_valid_returns201() throws Exception {
        ConciergeRequestCreateResponse response = ConciergeRequestCreateResponse.builder()
            .publicCode("C-2026-0001")
            .status("SUBMITTED")
            .existingUser(false)
            .accountSetupRequired(true)
            .message("Your concierge request is received. An account setup link has been sent to your email.")
            .build();
        when(conciergeService.submitRequest(any(), any())).thenReturn(response);

        mockMvc.perform(post("/api/public/concierge/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validBody())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.publicCode").value("C-2026-0001"))
            .andExpect(jsonPath("$.status").value("SUBMITTED"))
            .andExpect(jsonPath("$.existingUser").value(false))
            .andExpect(jsonPath("$.accountSetupRequired").value(true));
    }

    @Test
    @DisplayName("POST - pdpaConsent=false면 400 Validation error")
    void submit_pdpaConsentFalse_returns400() throws Exception {
        ConciergeRequestCreateRequest body = validBody();
        body.setPdpaConsent(false);

        mockMvc.perform(post("/api/public/concierge/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST - 필수 동의 4종 중 1개 누락이면 400")
    void submit_missingDelegationConsent_returns400() throws Exception {
        ConciergeRequestCreateRequest body = validBody();
        body.setDelegationConsent(false);

        mockMvc.perform(post("/api/public/concierge/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST - 잘못된 이메일 포맷은 400")
    void submit_invalidEmail_returns400() throws Exception {
        ConciergeRequestCreateRequest body = validBody();
        body.setEmail("not-an-email");

        mockMvc.perform(post("/api/public/concierge/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST - Service에서 ACCOUNT_NOT_ELIGIBLE 던지면 409")
    void submit_notEligible_returns409() throws Exception {
        when(conciergeService.submitRequest(any(), any()))
            .thenThrow(new BusinessException("not eligible", HttpStatus.CONFLICT, "ACCOUNT_NOT_ELIGIBLE"));

        mockMvc.perform(post("/api/public/concierge/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validBody())))
            .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST - Service에서 STAFF_EMAIL_NOT_ALLOWED 던지면 422")
    void submit_staffBlocked_returns422() throws Exception {
        when(conciergeService.submitRequest(any(), any()))
            .thenThrow(new BusinessException("staff blocked",
                HttpStatus.UNPROCESSABLE_ENTITY, "STAFF_EMAIL_NOT_ALLOWED"));

        mockMvc.perform(post("/api/public/concierge/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validBody())))
            .andExpect(status().isUnprocessableEntity());
    }
}

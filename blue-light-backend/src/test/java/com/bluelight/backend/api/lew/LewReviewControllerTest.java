package com.bluelight.backend.api.lew;

import com.bluelight.backend.api.application.dto.ApplicationResponse;
import com.bluelight.backend.api.lew.dto.LewApplicationResponse;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.common.exception.GlobalExceptionHandler;
import com.bluelight.backend.common.exception.LewReviewErrorCode;
import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.Auditable;
import com.bluelight.backend.service.lewreview.LewReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * LewReviewController 웹 레이어 테스트.
 *
 * <p>Standalone MockMvc — {@code @PreAuthorize} / {@code @Auditable} AOP는 작동하지 않는다.
 * 인가(@appSec.isAssignedLew)는 서비스 단 {@code assertAssignedLew}에서도 동일한
 * {@link LewReviewErrorCode#APPLICATION_NOT_ASSIGNED}를 던지므로(layer-defense) 서비스 레벨에서 검증.</p>
 *
 * <p>커버: 배정 신청 조회(GET) + 결제 요청(request-payment). CoF 기능은 제거되었다.</p>
 */
@DisplayName("LewReviewController")
class LewReviewControllerTest {

    private static final long LEW_SEQ = 10L;
    private static final long APP_SEQ = 1L;

    private LewReviewService service;
    private MockMvc mockMvc;

    private Authentication lewAuth() {
        return new UsernamePasswordAuthenticationToken(
            LEW_SEQ, null, List.of(new SimpleGrantedAuthority("ROLE_LEW")));
    }

    private MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder b) {
        return b.principal(lewAuth());
    }

    @BeforeEach
    void setUp() {
        service = mock(LewReviewService.class);
        mockMvc = MockMvcBuilders
            .standaloneSetup(new LewReviewController(service))
            .setControllerAdvice(new GlobalExceptionHandler(mock(com.bluelight.backend.api.audit.AuditLogService.class)))
            .build();
        SecurityContextHolder.getContext().setAuthentication(lewAuth());
    }

    // ── 미배정 LEW 403 ──────────────────────

    @Test
    @DisplayName("미배정 LEW가 GET 호출 시 서비스가 403 APPLICATION_NOT_ASSIGNED를 반환")
    void get_unassigned_lew_403() throws Exception {
        when(service.getAssignedApplication(eq(APP_SEQ), eq(LEW_SEQ)))
            .thenThrow(new BusinessException(
                "You are not assigned to this application",
                HttpStatus.FORBIDDEN, LewReviewErrorCode.APPLICATION_NOT_ASSIGNED));

        mockMvc.perform(withAuth(get("/api/lew/applications/{id}", APP_SEQ)))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET에 @Auditable(APPLICATION_VIEWED_BY_LEW) 부착 확인")
    void get_has_viewed_auditable() throws Exception {
        Method m = LewReviewController.class.getMethod(
            "getAssignedApplication", Long.class, Authentication.class);
        Auditable auditable = m.getAnnotation(Auditable.class);
        assertThat(auditable).isNotNull();
        assertThat(auditable.action()).isEqualTo(AuditAction.APPLICATION_VIEWED_BY_LEW);
    }

    @Test
    @DisplayName("GET 응답에 신청자 hint 값들이 직렬화되어 포함됨")
    void get_includes_hint_values() throws Exception {
        ApplicationResponse appRes = ApplicationResponse.builder()
            .applicationSeq(APP_SEQ)
            .address("1 Test Rd").postalCode("111111").selectedKva(45)
            .quoteAmount(new BigDecimal("100.00"))
            .status(ApplicationStatus.PENDING_REVIEW)
            .applicationType("NEW")
            .msslHintLast4("7890")
            .supplyVoltageHint(400)
            .consumerTypeHint("NON_CONTESTABLE")
            .retailerHint("SP_SERVICES_LIMITED")
            .hasGeneratorHint(true)
            .generatorCapacityHint(50)
            .build();
        LewApplicationResponse lewRes = LewApplicationResponse.builder()
            .application(appRes)
            .landlordEiLicenceNo("LEW-PLAIN")
            .msslHintLast4("7890")
            .supplyVoltageHint(400)
            .consumerTypeHint("NON_CONTESTABLE")
            .retailerHint("SP_SERVICES_LIMITED")
            .hasGeneratorHint(true)
            .generatorCapacityHint(50)
            .msslHintProvided(true)
            .supplyVoltageHintProvided(true)
            .consumerTypeHintProvided(true)
            .retailerHintProvided(true)
            .generatorHintProvided(true)
            .build();
        when(service.getAssignedApplication(eq(APP_SEQ), eq(LEW_SEQ))).thenReturn(lewRes);

        mockMvc.perform(withAuth(get("/api/lew/applications/{id}", APP_SEQ)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.msslHintLast4").value("7890"))
            .andExpect(jsonPath("$.supplyVoltageHint").value(400))
            .andExpect(jsonPath("$.consumerTypeHint").value("NON_CONTESTABLE"))
            .andExpect(jsonPath("$.retailerHint").value("SP_SERVICES_LIMITED"))
            .andExpect(jsonPath("$.hasGeneratorHint").value(true))
            .andExpect(jsonPath("$.generatorCapacityHint").value(50))
            .andExpect(jsonPath("$.msslHintProvided").value(true))
            .andExpect(jsonPath("$.supplyVoltageHintProvided").value(true))
            .andExpect(jsonPath("$.landlordEiLicenceNo").value("LEW-PLAIN"));
    }

    // ── request-payment ──────────────────────

    @Test
    @DisplayName("request-payment 성공 시 응답 status=PENDING_PAYMENT")
    void request_payment_success_returns_pending_payment() throws Exception {
        ApplicationResponse appRes = ApplicationResponse.builder()
            .applicationSeq(APP_SEQ)
            .address("1 Test Rd").postalCode("111111").selectedKva(45)
            .quoteAmount(new BigDecimal("100.00"))
            .status(ApplicationStatus.PENDING_PAYMENT)
            .applicationType("NEW")
            .build();
        when(service.requestPayment(eq(APP_SEQ), eq(LEW_SEQ), anyBoolean())).thenReturn(appRes);

        mockMvc.perform(withAuth(post("/api/lew/applications/{id}/request-payment", APP_SEQ)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
    }

    @Test
    @DisplayName("request-payment 시 status 전제 위반은 409 INVALID_STATUS_TRANSITION")
    void request_payment_invalid_transition_409() throws Exception {
        when(service.requestPayment(eq(APP_SEQ), eq(LEW_SEQ), anyBoolean()))
            .thenThrow(new BusinessException(
                "Already at PENDING_PAYMENT", HttpStatus.CONFLICT,
                LewReviewErrorCode.INVALID_STATUS_TRANSITION));

        mockMvc.perform(withAuth(post("/api/lew/applications/{id}/request-payment", APP_SEQ)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    @DisplayName("request-payment 시 kVA 미확정은 409 KVA_NOT_CONFIRMED")
    void request_payment_kva_not_confirmed_409() throws Exception {
        when(service.requestPayment(eq(APP_SEQ), eq(LEW_SEQ), anyBoolean()))
            .thenThrow(new BusinessException(
                "kVA must be confirmed", HttpStatus.CONFLICT,
                LewReviewErrorCode.KVA_NOT_CONFIRMED));

        mockMvc.perform(withAuth(post("/api/lew/applications/{id}/request-payment", APP_SEQ)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("KVA_NOT_CONFIRMED"));
    }

    // (제거됨) 미해결 서류 요청 게이트 — 2026-06-18 결정으로 문서는 결제 요청을 막지 않음(kVA 확정이 충분조건).

    @Test
    @DisplayName("request-payment 미배정 LEW 호출은 403 APPLICATION_NOT_ASSIGNED")
    void request_payment_unassigned_lew_403() throws Exception {
        when(service.requestPayment(eq(APP_SEQ), eq(LEW_SEQ), anyBoolean()))
            .thenThrow(new BusinessException(
                "Not assigned", HttpStatus.FORBIDDEN,
                LewReviewErrorCode.APPLICATION_NOT_ASSIGNED));

        mockMvc.perform(withAuth(post("/api/lew/applications/{id}/request-payment", APP_SEQ)))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("request-payment 메서드에 @Auditable(APPLICATION_PAYMENT_REQUESTED_BY_LEW) 부착 확인")
    void request_payment_has_auditable() throws Exception {
        Method m = LewReviewController.class.getMethod(
            "requestPayment", Long.class, boolean.class, Authentication.class);
        Auditable auditable = m.getAnnotation(Auditable.class);
        assertThat(auditable).as("@Auditable 누락 — 감사 로그가 기록되지 않음").isNotNull();
        assertThat(auditable.action()).isEqualTo(AuditAction.APPLICATION_PAYMENT_REQUESTED_BY_LEW);
    }
}

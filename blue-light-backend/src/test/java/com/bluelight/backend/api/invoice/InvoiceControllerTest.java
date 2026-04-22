package com.bluelight.backend.api.invoice;

import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.common.exception.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * InvoiceController 웹 레이어 테스트.
 *
 * <p>Standalone MockMvc — {@code @PreAuthorize} AOP는 작동하지 않는다.
 * AC-11 (LEW 403 차단)은 컨트롤러 메서드에 부착된 {@code @PreAuthorize} 어노테이션 값을
 * 리플렉션으로 검증한다. 실제 403 차단은 Spring Security 통합 환경에서 보장됨.
 *
 * <p>커버 AC:
 * <ul>
 *   <li>AC-11 — LEW 접근 차단: {@code @PreAuthorize("!hasRole('LEW')")} 어노테이션 부착 확인</li>
 *   <li>재발행 사유 검증 — 빈 reason POST → 400</li>
 * </ul>
 */
@DisplayName("InvoiceController - AC-11 + 재발행 사유 검증")
class InvoiceControllerTest {

    private InvoiceService invoiceService;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        invoiceService = mock(InvoiceService.class);
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new InvoiceController(invoiceService))
                // GlobalExceptionHandler를 장착하여 BusinessException/MethodArgumentNotValidException
                // → HTTP status 매핑을 실제 운영과 동일하게 재현
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        // 기본 Authentication: 일반 사용자 (userSeq=10)
        Authentication auth = new UsernamePasswordAuthenticationToken(
                10L, null, List.of(new SimpleGrantedAuthority("ROLE_APPLICANT")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    private Authentication lewAuth() {
        return new UsernamePasswordAuthenticationToken(
                77L, null, List.of(new SimpleGrantedAuthority("ROLE_LEW")));
    }

    private Authentication adminAuth() {
        return new UsernamePasswordAuthenticationToken(
                88L, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder b, Authentication auth) {
        return b.principal(auth);
    }

    private InvoiceResponse sampleResponse() {
        return InvoiceResponse.builder()
                .invoiceSeq(55L)
                .invoiceNumber("IN20260422001")
                .paymentSeq(101L)
                .referenceType("APPLICATION")
                .referenceSeq(1L)
                .applicationSeq(1L)
                .issuedAt(LocalDateTime.of(2026, 4, 22, 10, 0))
                .totalAmount(new BigDecimal("350.00"))
                .currency("SGD")
                .pdfFileSeq(999L)
                .billingRecipientName("Tan Wei Ming")
                .billingRecipientCompany(null)
                .build();
    }

    // ── AC-11: LEW 403 차단 (@PreAuthorize 어노테이션 검증) ─────────────────

    @Test
    @DisplayName("shouldHavePreAuthorizeExcludingLEWOnGetMyInvoice")
    void shouldHavePreAuthorizeExcludingLEWOnGetMyInvoice() throws NoSuchMethodException {
        // AC-11: GET /api/applications/{id}/invoice 에 @PreAuthorize("!hasRole('LEW')") 부착 확인
        // → LEW 계정이 접근하면 403이 발생하도록 어노테이션 설정됨을 검증
        Method m = InvoiceController.class.getMethod("getMyInvoice", Authentication.class, Long.class);
        PreAuthorize preAuthorize = m.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize)
                .as("@PreAuthorize 누락 — LEW가 GET /api/applications/{id}/invoice에 접근 가능해짐")
                .isNotNull();
        assertThat(preAuthorize.value())
                .as("LEW를 차단하는 표현식이어야 함")
                .isEqualTo("!hasRole('LEW')");
    }

    @Test
    @DisplayName("shouldHavePreAuthorizeAdminOnGetInvoiceAsAdmin")
    void shouldHavePreAuthorizeAdminOnGetInvoiceAsAdmin() throws NoSuchMethodException {
        // AC-11: GET /api/admin/applications/{id}/invoice 에 ADMIN/SYSTEM_ADMIN 제한 확인
        Method m = InvoiceController.class.getMethod("getInvoiceAsAdmin", Authentication.class, Long.class);
        PreAuthorize preAuthorize = m.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value())
                .contains("ADMIN")
                .contains("SYSTEM_ADMIN");
    }

    @Test
    @DisplayName("shouldHavePreAuthorizeAdminOnRegenerate")
    void shouldHavePreAuthorizeAdminOnRegenerate() throws NoSuchMethodException {
        // AC-11: POST /api/admin/applications/{id}/invoice/regenerate 에 ADMIN/SYSTEM_ADMIN 제한 확인
        Method m = InvoiceController.class.getMethod(
                "regenerate", Authentication.class, Long.class,
                InvoiceController.RegenerateRequest.class);
        PreAuthorize preAuthorize = m.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value())
                .contains("ADMIN")
                .contains("SYSTEM_ADMIN");
    }

    // ── 재발행 사유 검증: 빈 reason → 400 ───────────────────────────────────

    @Test
    @DisplayName("shouldReturn400WhenReasonIsBlankOnRegenerate")
    void shouldReturn400WhenReasonIsBlankOnRegenerate() throws Exception {
        // AC-11 / 재발행 사유 검증: 빈 reason으로 POST → 400 (Bean Validation @NotBlank)
        // Given
        String requestBody = "{\"reason\":\"\"}";

        // When / Then
        mockMvc.perform(
                withAuth(post("/api/admin/applications/1/invoice/regenerate"), adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn400WhenReasonIsMissingOnRegenerate")
    void shouldReturn400WhenReasonIsMissingOnRegenerate() throws Exception {
        // 재발행 사유 검증: reason 필드 누락 → 400
        // Given
        String requestBody = "{}";

        // When / Then
        mockMvc.perform(
                withAuth(post("/api/admin/applications/1/invoice/regenerate"), adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());
    }

    // ── 정상 흐름 — GET (인증 사용자) ────────────────────────────────────────

    @Test
    @DisplayName("shouldReturn200WhenApplicantGetsOwnInvoice")
    void shouldReturn200WhenApplicantGetsOwnInvoice() throws Exception {
        // Given
        Authentication auth = new UsernamePasswordAuthenticationToken(
                10L, null, List.of(new SimpleGrantedAuthority("ROLE_APPLICANT")));
        when(invoiceService.getByApplicationForApplicant(1L, 10L)).thenReturn(sampleResponse());

        // When / Then
        mockMvc.perform(
                withAuth(get("/api/applications/1/invoice"), auth))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("shouldReturn200WhenAdminGetsInvoice")
    void shouldReturn200WhenAdminGetsInvoice() throws Exception {
        // Given
        when(invoiceService.getByApplicationForAdmin(1L, 88L)).thenReturn(sampleResponse());

        // When / Then
        mockMvc.perform(
                withAuth(get("/api/admin/applications/1/invoice"), adminAuth()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("shouldReturn200WhenRegenerateCalledWithValidReason")
    void shouldReturn200WhenRegenerateCalledWithValidReason() throws Exception {
        // Given
        String requestBody = "{\"reason\":\"Admin corrected formatting error\"}";
        when(invoiceService.regenerate(1L, 88L, "Admin corrected formatting error"))
                .thenReturn(sampleResponse());

        // When / Then
        mockMvc.perform(
                withAuth(post("/api/admin/applications/1/invoice/regenerate"), adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk());
    }

    // ── 서비스 예외 → HTTP 매핑 확인 ─────────────────────────────────────────

    @Test
    @DisplayName("shouldReturn404WhenInvoiceNotFound")
    void shouldReturn404WhenInvoiceNotFound() throws Exception {
        // Given
        Authentication auth = new UsernamePasswordAuthenticationToken(
                10L, null, List.of(new SimpleGrantedAuthority("ROLE_APPLICANT")));
        when(invoiceService.getByApplicationForApplicant(1L, 10L))
                .thenThrow(new BusinessException("Invoice not yet available",
                        HttpStatus.NOT_FOUND, "INVOICE_NOT_FOUND"));

        // When / Then
        mockMvc.perform(
                withAuth(get("/api/applications/1/invoice"), auth))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("shouldReturn400WhenPaymentNotConfirmed")
    void shouldReturn400WhenPaymentNotConfirmed() throws Exception {
        // AC-10: 컨트롤러 → 서비스 → PAYMENT_NOT_CONFIRMED 400 매핑 확인
        // Given
        Authentication auth = new UsernamePasswordAuthenticationToken(
                10L, null, List.of(new SimpleGrantedAuthority("ROLE_APPLICANT")));
        when(invoiceService.getByApplicationForApplicant(1L, 10L))
                .thenThrow(new BusinessException("Payment not confirmed yet",
                        HttpStatus.BAD_REQUEST, "PAYMENT_NOT_CONFIRMED"));

        // When / Then
        mockMvc.perform(
                withAuth(get("/api/applications/1/invoice"), auth))
                .andExpect(status().isBadRequest());
    }
}

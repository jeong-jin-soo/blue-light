package com.bluelight.backend.api.lew;

import com.bluelight.backend.api.application.dto.ApplicationResponse;
import com.bluelight.backend.api.lew.dto.CertificateOfFitnessRequest;
import com.bluelight.backend.api.lew.dto.CertificateOfFitnessResponse;
import com.bluelight.backend.api.lew.dto.LewApplicationResponse;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.common.exception.CofErrorCode;
import com.bluelight.backend.common.exception.GlobalExceptionHandler;
import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.Auditable;
import com.bluelight.backend.domain.cof.ConsumerType;
import com.bluelight.backend.domain.cof.RetailerCode;
import com.bluelight.backend.service.cof.LewReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * LewReviewController 웹 레이어 테스트 (LEW Review Form P1.C).
 *
 * <p>Standalone MockMvc — {@code @PreAuthorize} / {@code @Auditable} AOP는 작동하지 않는다.
 * 인가(@appSec.isAssignedLew)는 서비스 단 {@code assertAssignedLew}에서도 동일한
 * {@link CofErrorCode#APPLICATION_NOT_ASSIGNED}를 던지므로(layer-defense) 서비스 레벨에서 검증.
 * AOP {@link Auditable} 검증은 "어노테이션이 올바른 AuditAction에 매핑되어 있는가"를 리플렉션으로.</p>
 *
 * <p>커버 AC (스펙 §9):
 * <ul>
 *   <li>AC 3 — 미배정 LEW 403</li>
 *   <li>AC 5 — finalize 후 재호출 409</li>
 *   <li>AC 6 — has_generator=true & capacity=null 400</li>
 *   <li>AC 7 — Contestable + retailer 누락 400</li>
 *   <li>AC 9 — MSSL 공란 Draft Save 200 / Finalize 400</li>
 *   <li>AC 10 — finalize 성공 → PENDING_PAYMENT + Auditable(FINALIZED) 매핑</li>
 *   <li>AC 18 — GET 응답에 hint 값이 포함</li>
 * </ul>
 */
@DisplayName("LewReviewController - P1.C")
class LewReviewControllerTest {

    private static final long LEW_SEQ = 10L;
    private static final long APP_SEQ = 1L;

    private LewReviewService service;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

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
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        mockMvc = MockMvcBuilders
            .standaloneSetup(new LewReviewController(service))
            // GlobalExceptionHandler advice를 장착하여 실제 운영과 동일한 예외→응답 매핑을 재현.
            // BusinessException, MethodArgumentNotValidException, ObjectOptimisticLockingFailureException을
            // 모두 advice가 처리한다. 별도 custom resolver를 세팅하면 advice resolver가 대체되므로 쓰지 않는다.
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        SecurityContextHolder.getContext().setAuthentication(lewAuth());
    }

    private CertificateOfFitnessRequest validReqBody() {
        CertificateOfFitnessRequest r = new CertificateOfFitnessRequest();
        r.setMsslAccountNo("123-45-6789-0");
        r.setConsumerType(ConsumerType.NON_CONTESTABLE);
        r.setRetailerCode(RetailerCode.SP_SERVICES_LIMITED);
        r.setSupplyVoltageV(400);
        r.setApprovedLoadKva(45);
        r.setHasGenerator(false);
        r.setInspectionIntervalMonths(12);
        r.setLewAppointmentDate(LocalDate.of(2026, 4, 22));
        return r;
    }

    private CertificateOfFitnessResponse sampleCofResponse(boolean finalized) {
        return CertificateOfFitnessResponse.builder()
            .cofSeq(100L)
            .applicationSeq(APP_SEQ)
            .msslAccountNo("123-45-6789-0")
            .msslAccountNoLast4("7890")
            .consumerType(ConsumerType.NON_CONTESTABLE)
            .retailerCode(RetailerCode.SP_SERVICES_LIMITED)
            .supplyVoltageV(400)
            .approvedLoadKva(45)
            .hasGenerator(false)
            .inspectionIntervalMonths(12)
            .lewAppointmentDate(LocalDate.of(2026, 4, 22))
            .draftSavedAt(LocalDateTime.now())
            .version(0)
            .finalized(finalized)
            .build();
    }

    // ── AC 3: 미배정 LEW ──────────────────────

    @Test
    @DisplayName("AC3 - 미배정 LEW가 GET 호출 시 서비스가 403 APPLICATION_NOT_ASSIGNED를 반환")
    void ac3_get_unassigned_lew_403() throws Exception {
        when(service.getAssignedApplication(eq(APP_SEQ), eq(LEW_SEQ)))
            .thenThrow(new BusinessException(
                "You are not assigned to this application",
                HttpStatus.FORBIDDEN, CofErrorCode.APPLICATION_NOT_ASSIGNED));

        mockMvc.perform(withAuth(get("/api/lew/applications/{id}", APP_SEQ)))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AC3 - 미배정 LEW가 PUT /cof 호출 시 403")
    void ac3_put_unassigned_lew_403() throws Exception {
        when(service.saveDraftCof(eq(APP_SEQ), eq(LEW_SEQ), any()))
            .thenThrow(new BusinessException(
                "You are not assigned", HttpStatus.FORBIDDEN,
                CofErrorCode.APPLICATION_NOT_ASSIGNED));

        mockMvc.perform(withAuth(put("/api/lew/applications/{id}/cof", APP_SEQ))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validReqBody())))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AC3 - 미배정 LEW가 POST /cof/finalize 호출 시 403")
    void ac3_post_finalize_unassigned_lew_403() throws Exception {
        when(service.finalizeCof(eq(APP_SEQ), eq(LEW_SEQ)))
            .thenThrow(new BusinessException(
                "You are not assigned", HttpStatus.FORBIDDEN,
                CofErrorCode.APPLICATION_NOT_ASSIGNED));

        mockMvc.perform(withAuth(post("/api/lew/applications/{id}/cof/finalize", APP_SEQ)))
            .andExpect(status().isForbidden());
    }

    // ── AC 5: finalize 후 재호출 409 ──────────────────────

    @Test
    @DisplayName("AC5 - finalize 후 재호출 시 409 COF_ALREADY_FINALIZED")
    void ac5_finalize_twice_409() throws Exception {
        when(service.finalizeCof(eq(APP_SEQ), eq(LEW_SEQ)))
            .thenThrow(new BusinessException(
                "Already finalized", HttpStatus.CONFLICT,
                CofErrorCode.COF_ALREADY_FINALIZED));

        mockMvc.perform(withAuth(post("/api/lew/applications/{id}/cof/finalize", APP_SEQ)))
            .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("AC5 - finalized 상태에서 Draft Save도 409")
    void ac5_draft_save_on_finalized_409() throws Exception {
        when(service.saveDraftCof(eq(APP_SEQ), eq(LEW_SEQ), any()))
            .thenThrow(new BusinessException(
                "Already finalized", HttpStatus.CONFLICT,
                CofErrorCode.COF_ALREADY_FINALIZED));

        mockMvc.perform(withAuth(put("/api/lew/applications/{id}/cof", APP_SEQ))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validReqBody())))
            .andExpect(status().isConflict());
    }

    // ── AC 6: has_generator=true & capacity=null → 400 ──────────────────────

    @Test
    @DisplayName("AC6 - finalize 시 has_generator=true & capacity=null은 400 COF_VALIDATION_FAILED")
    void ac6_finalize_generator_without_capacity_400() throws Exception {
        when(service.finalizeCof(eq(APP_SEQ), eq(LEW_SEQ)))
            .thenThrow(new BusinessException(
                "Generator capacity required", HttpStatus.BAD_REQUEST,
                CofErrorCode.COF_VALIDATION_FAILED));

        mockMvc.perform(withAuth(post("/api/lew/applications/{id}/cof/finalize", APP_SEQ)))
            .andExpect(status().isBadRequest());
    }

    // ── AC 7: Contestable + retailer 누락 400 ──────────────────────

    @Test
    @DisplayName("AC7 - finalize 시 Contestable + retailer 누락은 400")
    void ac7_finalize_contestable_without_retailer_400() throws Exception {
        when(service.finalizeCof(eq(APP_SEQ), eq(LEW_SEQ)))
            .thenThrow(new BusinessException(
                "Retailer required for contestable", HttpStatus.BAD_REQUEST,
                CofErrorCode.COF_VALIDATION_FAILED));

        mockMvc.perform(withAuth(post("/api/lew/applications/{id}/cof/finalize", APP_SEQ)))
            .andExpect(status().isBadRequest());
    }

    // ── AC 9: MSSL 공란 Draft Save 200, Finalize 400 ──────────────────────

    @Test
    @DisplayName("AC9 - Draft Save에서 MSSL 공란이어도 200")
    void ac9_draft_save_blank_mssl_200() throws Exception {
        CertificateOfFitnessResponse res = CertificateOfFitnessResponse.builder()
            .cofSeq(100L).applicationSeq(APP_SEQ)
            .msslAccountNo(null).msslAccountNoLast4(null)
            .supplyVoltageV(400).approvedLoadKva(45).inspectionIntervalMonths(12)
            .lewAppointmentDate(LocalDate.of(2026, 4, 22))
            .draftSavedAt(LocalDateTime.now())
            .version(0).finalized(false)
            .build();
        when(service.saveDraftCof(eq(APP_SEQ), eq(LEW_SEQ), any())).thenReturn(res);

        CertificateOfFitnessRequest req = validReqBody();
        req.setMsslAccountNo(null); // 공란

        mockMvc.perform(withAuth(put("/api/lew/applications/{id}/cof", APP_SEQ))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.msslAccountNo").doesNotExist())
            .andExpect(jsonPath("$.msslAccountNoLast4").doesNotExist());
    }

    @Test
    @DisplayName("AC9 - finalize에서 MSSL 공란이면 400 COF_VALIDATION_FAILED")
    void ac9_finalize_blank_mssl_400() throws Exception {
        when(service.finalizeCof(eq(APP_SEQ), eq(LEW_SEQ)))
            .thenThrow(new BusinessException(
                "MSSL required", HttpStatus.BAD_REQUEST,
                CofErrorCode.COF_VALIDATION_FAILED));

        mockMvc.perform(withAuth(post("/api/lew/applications/{id}/cof/finalize", APP_SEQ)))
            .andExpect(status().isBadRequest());
    }

    // ── AC 10: finalize 성공 시 status=PENDING_PAYMENT + Auditable 매핑 ──────────────────────

    @Test
    @DisplayName("AC10 - PR3 옵션 R: finalize 성공 시 응답 status=PAID (전이 없음)")
    void ac10_finalize_success_returns_paid_status() throws Exception {
        // PR3 이전: PENDING_PAYMENT 로 전이. PR3 이후: status 전이 없음 — 호출 전 상태 유지(PAID/IN_PROGRESS).
        ApplicationResponse appRes = ApplicationResponse.builder()
            .applicationSeq(APP_SEQ)
            .address("1 Test Rd").postalCode("111111").selectedKva(45)
            .quoteAmount(new BigDecimal("100.00"))
            .status(ApplicationStatus.PAID)
            .applicationType("NEW")
            .build();
        when(service.finalizeCof(eq(APP_SEQ), eq(LEW_SEQ))).thenReturn(appRes);

        mockMvc.perform(withAuth(post("/api/lew/applications/{id}/cof/finalize", APP_SEQ)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    @DisplayName("PR3 - finalize 시 결제 미완료(APPLICATION_NOT_PAID)이면 409")
    void pr3_finalize_application_not_paid_409() throws Exception {
        when(service.finalizeCof(eq(APP_SEQ), eq(LEW_SEQ)))
            .thenThrow(new BusinessException(
                "Payment must be confirmed before finalizing CoF",
                HttpStatus.CONFLICT, CofErrorCode.APPLICATION_NOT_PAID));

        mockMvc.perform(withAuth(post("/api/lew/applications/{id}/cof/finalize", APP_SEQ)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("APPLICATION_NOT_PAID"));
    }

    @Test
    @DisplayName("AC10 - finalize 컨트롤러 메서드에 @Auditable(CERTIFICATE_OF_FITNESS_FINALIZED) 부착 확인")
    void ac10_finalize_has_auditable_annotation() throws Exception {
        Method m = LewReviewController.class.getMethod(
            "finalizeCof", Long.class, Authentication.class);
        Auditable auditable = m.getAnnotation(Auditable.class);
        assertThat(auditable).as("@Auditable 누락 — 감사 로그가 기록되지 않음").isNotNull();
        assertThat(auditable.action()).isEqualTo(AuditAction.CERTIFICATE_OF_FITNESS_FINALIZED);
    }

    @Test
    @DisplayName("AC10 - GET에 @Auditable(APPLICATION_VIEWED_BY_LEW) 부착 확인")
    void ac10_get_has_viewed_auditable() throws Exception {
        Method m = LewReviewController.class.getMethod(
            "getAssignedApplication", Long.class, Authentication.class);
        Auditable auditable = m.getAnnotation(Auditable.class);
        assertThat(auditable).isNotNull();
        assertThat(auditable.action()).isEqualTo(AuditAction.APPLICATION_VIEWED_BY_LEW);
    }

    @Test
    @DisplayName("AC10 - PUT /cof에 @Auditable(CERTIFICATE_OF_FITNESS_UPDATED) 부착 확인")
    void ac10_put_has_updated_auditable() throws Exception {
        Method m = LewReviewController.class.getMethod(
            "saveDraftCof", Long.class, CertificateOfFitnessRequest.class, Authentication.class);
        Auditable auditable = m.getAnnotation(Auditable.class);
        assertThat(auditable).isNotNull();
        assertThat(auditable.action()).isEqualTo(AuditAction.CERTIFICATE_OF_FITNESS_UPDATED);
    }

    // ── AC 18: GET 응답에 hint 값 포함 ──────────────────────

    @Test
    @DisplayName("AC18 - GET 응답에 신청자 hint 값들이 직렬화되어 포함됨")
    void ac18_get_includes_hint_values() throws Exception {
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
            .cof(null)
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
            // "신청자 기입값" 배지 플래그
            .andExpect(jsonPath("$.msslHintProvided").value(true))
            .andExpect(jsonPath("$.supplyVoltageHintProvided").value(true))
            // LEW 전용 평문 필드 (applicant 응답에서는 마스킹/은닉)
            .andExpect(jsonPath("$.landlordEiLicenceNo").value("LEW-PLAIN"));
    }

    // ── Draft Save 정상 경로 ──────────────────────

    @Test
    @DisplayName("PUT /cof 정상 - 200 + CoF 응답 필드 직렬화")
    void put_cof_success_returns_200() throws Exception {
        when(service.saveDraftCof(eq(APP_SEQ), eq(LEW_SEQ), any()))
            .thenReturn(sampleCofResponse(false));

        mockMvc.perform(withAuth(put("/api/lew/applications/{id}/cof", APP_SEQ))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validReqBody())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cofSeq").value(100))
            .andExpect(jsonPath("$.msslAccountNo").value("123-45-6789-0"))
            .andExpect(jsonPath("$.msslAccountNoLast4").value("7890"))
            .andExpect(jsonPath("$.finalized").value(false));
    }

    // ── AC 12: 낙관적 락 충돌 → 409 COF_VERSION_CONFLICT ──────────────────────

    @Test
    @DisplayName("AC12 - PUT /cof 에서 ObjectOptimisticLockingFailureException 발생 시 409 COF_VERSION_CONFLICT")
    void ac12_put_cof_optimistic_lock_yields_cof_version_conflict() throws Exception {
        when(service.saveDraftCof(eq(APP_SEQ), eq(LEW_SEQ), any()))
            .thenThrow(new ObjectOptimisticLockingFailureException(
                "CertificateOfFitness", 100L));

        mockMvc.perform(withAuth(put("/api/lew/applications/{id}/cof", APP_SEQ))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validReqBody())))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("COF_VERSION_CONFLICT"));
    }

    @Test
    @DisplayName("AC12 - POST /cof/finalize 에서 낙관적 락 충돌 시 409 COF_VERSION_CONFLICT")
    void ac12_finalize_optimistic_lock_yields_cof_version_conflict() throws Exception {
        when(service.finalizeCof(eq(APP_SEQ), eq(LEW_SEQ)))
            .thenThrow(new ObjectOptimisticLockingFailureException(
                "CertificateOfFitness", 100L));

        mockMvc.perform(withAuth(post("/api/lew/applications/{id}/cof/finalize", APP_SEQ)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("COF_VERSION_CONFLICT"));
    }

    // ── PR3: POST /api/lew/applications/{id}/request-payment ──────────────────────

    @Test
    @DisplayName("PR3 - request-payment 성공 시 응답 status=PENDING_PAYMENT")
    void pr3_request_payment_success_returns_pending_payment() throws Exception {
        ApplicationResponse appRes = ApplicationResponse.builder()
            .applicationSeq(APP_SEQ)
            .address("1 Test Rd").postalCode("111111").selectedKva(45)
            .quoteAmount(new BigDecimal("100.00"))
            .status(ApplicationStatus.PENDING_PAYMENT)
            .applicationType("NEW")
            .build();
        when(service.requestPayment(eq(APP_SEQ), eq(LEW_SEQ))).thenReturn(appRes);

        mockMvc.perform(withAuth(post("/api/lew/applications/{id}/request-payment", APP_SEQ)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
    }

    @Test
    @DisplayName("PR3 - request-payment 시 status 전제 위반은 409 INVALID_STATUS_TRANSITION")
    void pr3_request_payment_invalid_transition_409() throws Exception {
        when(service.requestPayment(eq(APP_SEQ), eq(LEW_SEQ)))
            .thenThrow(new BusinessException(
                "Already at PENDING_PAYMENT", HttpStatus.CONFLICT,
                CofErrorCode.INVALID_STATUS_TRANSITION));

        mockMvc.perform(withAuth(post("/api/lew/applications/{id}/request-payment", APP_SEQ)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    @DisplayName("PR3 - request-payment 시 kVA 미확정은 409 KVA_NOT_CONFIRMED")
    void pr3_request_payment_kva_not_confirmed_409() throws Exception {
        when(service.requestPayment(eq(APP_SEQ), eq(LEW_SEQ)))
            .thenThrow(new BusinessException(
                "kVA must be confirmed", HttpStatus.CONFLICT,
                CofErrorCode.KVA_NOT_CONFIRMED));

        mockMvc.perform(withAuth(post("/api/lew/applications/{id}/request-payment", APP_SEQ)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("KVA_NOT_CONFIRMED"));
    }

    @Test
    @DisplayName("PR3 - request-payment 시 미해결 서류 요청은 409 DOCUMENT_REQUESTS_PENDING")
    void pr3_request_payment_documents_pending_409() throws Exception {
        when(service.requestPayment(eq(APP_SEQ), eq(LEW_SEQ)))
            .thenThrow(new BusinessException(
                "Pending docs", HttpStatus.CONFLICT,
                CofErrorCode.DOCUMENT_REQUESTS_PENDING));

        mockMvc.perform(withAuth(post("/api/lew/applications/{id}/request-payment", APP_SEQ)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("DOCUMENT_REQUESTS_PENDING"));
    }

    @Test
    @DisplayName("PR3 - request-payment 미배정 LEW 호출은 403 APPLICATION_NOT_ASSIGNED")
    void pr3_request_payment_unassigned_lew_403() throws Exception {
        when(service.requestPayment(eq(APP_SEQ), eq(LEW_SEQ)))
            .thenThrow(new BusinessException(
                "Not assigned", HttpStatus.FORBIDDEN,
                CofErrorCode.APPLICATION_NOT_ASSIGNED));

        mockMvc.perform(withAuth(post("/api/lew/applications/{id}/request-payment", APP_SEQ)))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PR3 - request-payment 메서드에 @Auditable(APPLICATION_PAYMENT_REQUESTED_BY_LEW) 부착 확인")
    void pr3_request_payment_has_auditable() throws Exception {
        Method m = LewReviewController.class.getMethod(
            "requestPayment", Long.class, Authentication.class);
        Auditable auditable = m.getAnnotation(Auditable.class);
        assertThat(auditable).as("@Auditable 누락 — 감사 로그가 기록되지 않음").isNotNull();
        assertThat(auditable.action()).isEqualTo(AuditAction.APPLICATION_PAYMENT_REQUESTED_BY_LEW);
    }
}

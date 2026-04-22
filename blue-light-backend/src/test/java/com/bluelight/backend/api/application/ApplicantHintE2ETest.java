package com.bluelight.backend.api.application;

import com.bluelight.backend.api.application.dto.ApplicationResponse;
import com.bluelight.backend.api.application.dto.CreateApplicationRequest;
import com.bluelight.backend.api.application.dto.UpdateApplicationRequest;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.service.application.ApplicantHintWarning;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Applicant hint E2E (MVC 레벨) 테스트 — LEW Review Form P1.C.
 *
 * <p>Standalone MockMvc 패턴. Service는 mock, HTTP 요청/응답의 hint·warnings 노출만 검증.</p>
 *
 * <p>커버 AC (스펙 §9):
 * <ul>
 *   <li>AC 2 — {@code PUT /api/applications/{id}} DTO에 CoF 필드 없음(리플렉션)</li>
 *   <li>AC 14 — hint 전 필드 비워도 POST 201</li>
 *   <li>AC 16 — hint 형식 오류여도 201 + {@code warnings[]}</li>
 *   <li>AC 보조 — hint 정상 제출 시 201 + {@code msslHintLast4}만 노출 (MSSL 평문·enc 미노출)</li>
 * </ul>
 */
@DisplayName("ApplicantHint E2E (MVC) - P1.C")
class ApplicantHintE2ETest {

    private static final long APPLICANT_SEQ = 7L;

    private ApplicationService applicationService;
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

    private Authentication applicantAuth() {
        return new UsernamePasswordAuthenticationToken(
            APPLICANT_SEQ, null, List.of(new SimpleGrantedAuthority("ROLE_APPLICANT")));
    }

    private MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder b) {
        return b.principal(applicantAuth());
    }

    @BeforeEach
    void setUp() {
        applicationService = mock(ApplicationService.class);
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        mockMvc = MockMvcBuilders
            .standaloneSetup(new ApplicationController(applicationService))
            .setHandlerExceptionResolvers(globalResolver())
            .build();
        SecurityContextHolder.getContext().setAuthentication(applicantAuth());
    }

    private CreateApplicationRequest baseCreateBody() {
        CreateApplicationRequest r = new CreateApplicationRequest();
        r.setAddress("1 Test Rd");
        r.setPostalCode("111111");
        r.setBuildingType("HDB_FLAT");
        r.setSelectedKva(45);
        r.setApplicantType(com.bluelight.backend.domain.application.ApplicantType.INDIVIDUAL);
        return r;
    }

    private ApplicationResponse sampleApplicationResponse(List<ApplicantHintWarning> warnings) {
        ApplicationResponse r = ApplicationResponse.builder()
            .applicationSeq(100L)
            .address("1 Test Rd").postalCode("111111").selectedKva(45)
            .quoteAmount(new BigDecimal("100.00"))
            .status(ApplicationStatus.PENDING_REVIEW)
            .applicationType("NEW")
            .build();
        return r.withWarnings(warnings);
    }

    // ── AC 2: PUT /api/applications/{id} DTO에 CoF 필드가 존재하지 않음 (리플렉션) ──

    @Test
    @DisplayName("AC2 - UpdateApplicationRequest에 CoF 고유 필드가 없다 (신청자가 CoF 수정 불가)")
    void ac2_update_dto_has_no_cof_fields() {
        // CoF 고유 필드 — 신청자가 PUT으로 수정할 수 없어야 함
        // (hint 필드는 허용됨. msslHint != msslAccountNo)
        Set<String> disallowed = Set.of(
            "msslAccountNo", "msslAccountNoEnc", "msslAccountNoHmac", "msslAccountNoLast4",
            "consumerType", "retailerCode", "supplyVoltageV", "approvedLoadKva",
            "inspectionIntervalMonths", "lewAppointmentDate", "lewConsentDate",
            "certifiedAt", "certifiedByLew", "certifiedByLewSeq"
        );
        Set<String> actualFields = Arrays.stream(UpdateApplicationRequest.class.getDeclaredFields())
            .map(Field::getName)
            .collect(java.util.stream.Collectors.toSet());

        for (String banned : disallowed) {
            assertThat(actualFields)
                .as("UpdateApplicationRequest must not expose CoF field '%s' to applicants", banned)
                .doesNotContain(banned);
        }
    }

    @Test
    @DisplayName("AC2 - CreateApplicationRequest에도 CoF 전용 필드 없음 (hint는 OK)")
    void ac2_create_dto_has_no_cof_fields() {
        Set<String> disallowed = Set.of(
            "msslAccountNo", "consumerType", "retailerCode", "supplyVoltageV",
            "approvedLoadKva", "inspectionIntervalMonths",
            "lewAppointmentDate", "lewConsentDate", "certifiedAt", "certifiedByLew"
        );
        Set<String> actualFields = Arrays.stream(CreateApplicationRequest.class.getDeclaredFields())
            .map(Field::getName)
            .collect(java.util.stream.Collectors.toSet());

        for (String banned : disallowed) {
            assertThat(actualFields)
                .as("CreateApplicationRequest must not expose CoF field '%s'", banned)
                .doesNotContain(banned);
        }
        // hint 필드는 허용 확인 (이름 sanity check)
        assertThat(actualFields).contains(
            "msslHint", "supplyVoltageHint", "consumerTypeHint",
            "retailerHint", "hasGeneratorHint", "generatorCapacityHint");
    }

    // ── AC 14: hint 전 필드 비워도 201 ──

    @Test
    @DisplayName("AC14 - hint 필드를 모두 비워도 POST /api/applications는 201")
    void ac14_empty_hints_still_201() throws Exception {
        when(applicationService.createApplication(anyLong(), any(), any(), any()))
            .thenReturn(sampleApplicationResponse(List.of()));

        // hint 필드 완전히 생략 (body에 키 자체가 없음)
        CreateApplicationRequest body = baseCreateBody();
        // body에 hint 관련 setter 호출하지 않음

        mockMvc.perform(withAuth(post("/api/applications"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.applicationSeq").value(100))
            // warnings 배열은 비어있거나 null
            .andExpect(result -> {
                String json = result.getResponse().getContentAsString();
                // Jackson이 빈 List도 []로 직렬화 — 둘 다 허용
                boolean ok = json.contains("\"warnings\":[]") || !json.contains("\"warnings\"");
                assertThat(ok).isTrue();
            });
    }

    // ── AC 16: hint 형식 오류여도 201 + warnings ──

    @Test
    @DisplayName("AC16 - MSSL 형식 오류 hint가 제출돼도 POST는 201 + warnings[0].field=msslHint")
    void ac16_invalid_mssl_hint_returns_201_with_warnings() throws Exception {
        ApplicantHintWarning warning = ApplicantHintWarning.builder()
            .field("msslHint")
            .code("INVALID_FORMAT")
            .reason("MSSL format invalid")
            .build();
        when(applicationService.createApplication(anyLong(), any(), any(), any()))
            .thenReturn(sampleApplicationResponse(List.of(warning)));

        CreateApplicationRequest body = baseCreateBody();
        body.setMsslHint("abc-de-fghi-j"); // 형식 오류

        mockMvc.perform(withAuth(post("/api/applications"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.applicationSeq").value(100))
            .andExpect(jsonPath("$.warnings[0].field").value("msslHint"))
            .andExpect(jsonPath("$.warnings[0].code").value("INVALID_FORMAT"));
    }

    @Test
    @DisplayName("AC16 - 여러 필드 동시 형식 오류도 각각 warning으로 수집, 201 유지")
    void ac16_multiple_invalid_hints_all_collected() throws Exception {
        List<ApplicantHintWarning> warnings = List.of(
            ApplicantHintWarning.builder().field("msslHint").code("INVALID_FORMAT").reason("r1").build(),
            ApplicantHintWarning.builder().field("supplyVoltageHint").code("INVALID_VALUE").reason("r2").build(),
            ApplicantHintWarning.builder().field("consumerTypeHint").code("INVALID_VALUE").reason("r3").build()
        );
        when(applicationService.createApplication(anyLong(), any(), any(), any()))
            .thenReturn(sampleApplicationResponse(warnings));

        CreateApplicationRequest body = baseCreateBody();
        body.setMsslHint("bad");
        body.setSupplyVoltageHint(999);
        body.setConsumerTypeHint("OOPS");

        mockMvc.perform(withAuth(post("/api/applications"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.warnings.length()").value(3));
    }

    // ── AC 보조: 정상 hint는 201 + last4만 노출 ──

    @Test
    @DisplayName("AC 보조 - 정상 hint 제출 시 201, 응답은 msslHintLast4만 (평문·enc 미노출)")
    void valid_hint_returns_last4_only() throws Exception {
        ApplicationResponse withHint = ApplicationResponse.builder()
            .applicationSeq(100L)
            .address("1 Test Rd").postalCode("111111").selectedKva(45)
            .quoteAmount(new BigDecimal("100.00"))
            .status(ApplicationStatus.PENDING_REVIEW)
            .applicationType("NEW")
            .msslHintLast4("7890")
            .supplyVoltageHint(400)
            .consumerTypeHint("NON_CONTESTABLE")
            .build()
            .withWarnings(List.of());

        when(applicationService.createApplication(anyLong(), any(), any(), any()))
            .thenReturn(withHint);

        CreateApplicationRequest body = baseCreateBody();
        body.setMsslHint("123-45-6789-0");
        body.setSupplyVoltageHint(400);
        body.setConsumerTypeHint("NON_CONTESTABLE");

        mockMvc.perform(withAuth(post("/api/applications"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.msslHintLast4").value("7890"))
            .andExpect(jsonPath("$.supplyVoltageHint").value(400))
            // 평문/암호문 필드는 존재해서도 안 된다
            .andExpect(jsonPath("$.msslHint").doesNotExist())
            .andExpect(jsonPath("$.msslAccountNo").doesNotExist())
            .andExpect(jsonPath("$.applicantMsslHintEnc").doesNotExist())
            .andExpect(jsonPath("$.applicantMsslHintHmac").doesNotExist());
    }
}

package com.bluelight.backend.api.lew;

import com.bluelight.backend.api.lew.dto.LewKvaAdjustmentRequest;
import com.bluelight.backend.api.lew.dto.LewKvaAdjustmentResponse;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.common.exception.GlobalExceptionHandler;
import com.bluelight.backend.domain.kva.KvaAdjustmentStatus;
import com.bluelight.backend.service.kva.KvaPostPaymentService;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PR-3 — {@link LewKvaAdjustmentController} 웹 레이어 테스트.
 *
 * <p>스펙: {@code doc/Project Analysis/kva-postpayment-adjustment-spec.md} §4.2.</p>
 *
 * <p>Standalone MockMvc — {@code @PreAuthorize}/{@code @appSec.isAssignedLew} AOP 미작동.
 * 본 테스트는 컨트롤러가 서비스에 정확한 인자를 전달하고, 서비스 예외를 GlobalExceptionHandler 가
 * 올바른 HTTP status + code 로 매핑하는지 검증한다 (PR-1 AdminKvaAdjustmentController 과 동일 패턴).</p>
 *
 * <h2>커버 AC</h2>
 * <ul>
 *   <li>AC-L1 — 정상 흐름 (PAID 상태) + 응답 payload 검증</li>
 *   <li>AC-L3 — PRE-PAYMENT 거부 → 409 KVA_NOT_POSTPAYMENT</li>
 *   <li>AC-L5 — 동일 application 의 PENDING 요청 존재 → 409 KVA_ADJUSTMENT_REQUEST_ALREADY_PENDING</li>
 *   <li>EXPIRED 거부 → 409 KVA_ADJUSTMENT_NOT_ALLOWED_EXPIRED</li>
 *   <li>동일 proposedKva → 400 KVA_NO_CHANGE</li>
 *   <li>master_prices 미존재 → 400 INVALID_KVA_TIER</li>
 *   <li>유효성 — proposedKva null → 400</li>
 * </ul>
 */
@DisplayName("LewKvaAdjustmentController — PR-3")
class LewKvaAdjustmentControllerTest {

    private static final long LEW_SEQ = 50L;
    private static final long APP_SEQ = 1L;

    private KvaPostPaymentService service;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private Authentication lewAuth() {
        return new UsernamePasswordAuthenticationToken(
                LEW_SEQ, null, List.of(new SimpleGrantedAuthority("ROLE_LEW")));
    }

    @BeforeEach
    void setUp() {
        service = mock(KvaPostPaymentService.class);
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new LewKvaAdjustmentController(service))
                .setControllerAdvice(new GlobalExceptionHandler(mock(com.bluelight.backend.api.audit.AuditLogService.class)))
                .build();
        SecurityContextHolder.getContext().setAuthentication(lewAuth());
    }

    private LewKvaAdjustmentRequest req(Integer proposedKva, String reason) {
        LewKvaAdjustmentRequest r = new LewKvaAdjustmentRequest();
        r.setProposedKva(proposedKva);
        r.setReason(reason);
        return r;
    }

    @Test
    @DisplayName("AC-L1 정상 흐름 — 200 OK + adjustmentSeq + status=PENDING_ADMIN_REVIEW")
    void AC_L1_정상() throws Exception {
        LewKvaAdjustmentResponse resp = LewKvaAdjustmentResponse.builder()
                .adjustmentSeq(42L)
                .status(KvaAdjustmentStatus.PENDING_ADMIN_REVIEW)
                .proposedKva(200)
                .currentKva(100)
                .reason("Site survey: actual load 180 kVA")
                .createdAt(LocalDateTime.of(2026, 5, 1, 10, 0))
                .build();
        when(service.requestAdjustmentByLew(eq(APP_SEQ), eq(LEW_SEQ), any()))
                .thenReturn(resp);

        mockMvc.perform(post("/api/lew/applications/" + APP_SEQ + "/kva-adjustment-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .principal(lewAuth())
                        .content(objectMapper.writeValueAsString(req(200, "Site survey: actual load 180 kVA"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adjustmentSeq").value(42))
                .andExpect(jsonPath("$.status").value("PENDING_ADMIN_REVIEW"))
                .andExpect(jsonPath("$.proposedKva").value(200))
                .andExpect(jsonPath("$.currentKva").value(100))
                .andExpect(jsonPath("$.reason").value("Site survey: actual load 180 kVA"));
    }

    @Test
    @DisplayName("AC-L3 PRE-PAYMENT 거부 — 409 KVA_NOT_POSTPAYMENT")
    void AC_L3_PRE_PAYMENT_거부() throws Exception {
        when(service.requestAdjustmentByLew(eq(APP_SEQ), eq(LEW_SEQ), any()))
                .thenThrow(new BusinessException(
                        "Use Phase 1 kVA confirmation flow for pre-payment changes",
                        HttpStatus.CONFLICT, "KVA_NOT_POSTPAYMENT"));

        mockMvc.perform(post("/api/lew/applications/" + APP_SEQ + "/kva-adjustment-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .principal(lewAuth())
                        .content(objectMapper.writeValueAsString(req(200, "x for ten chars"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KVA_NOT_POSTPAYMENT"));
    }

    @Test
    @DisplayName("AC-L5 중복 PENDING — 409 KVA_ADJUSTMENT_REQUEST_ALREADY_PENDING")
    void AC_L5_중복_PENDING_거부() throws Exception {
        when(service.requestAdjustmentByLew(eq(APP_SEQ), eq(LEW_SEQ), any()))
                .thenThrow(new BusinessException(
                        "A kVA adjustment request is already pending admin review for this application",
                        HttpStatus.CONFLICT, "KVA_ADJUSTMENT_REQUEST_ALREADY_PENDING"));

        mockMvc.perform(post("/api/lew/applications/" + APP_SEQ + "/kva-adjustment-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .principal(lewAuth())
                        .content(objectMapper.writeValueAsString(req(200, "Recommend tier change"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KVA_ADJUSTMENT_REQUEST_ALREADY_PENDING"));
    }

    @Test
    @DisplayName("EXPIRED 거부 — 409 KVA_ADJUSTMENT_NOT_ALLOWED_EXPIRED")
    void EXPIRED_거부() throws Exception {
        when(service.requestAdjustmentByLew(eq(APP_SEQ), eq(LEW_SEQ), any()))
                .thenThrow(new BusinessException(
                        "EXPIRED applications cannot be adjusted",
                        HttpStatus.CONFLICT, "KVA_ADJUSTMENT_NOT_ALLOWED_EXPIRED"));

        mockMvc.perform(post("/api/lew/applications/" + APP_SEQ + "/kva-adjustment-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .principal(lewAuth())
                        .content(objectMapper.writeValueAsString(req(200, "Late request"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KVA_ADJUSTMENT_NOT_ALLOWED_EXPIRED"));
    }

    @Test
    @DisplayName("동일 proposedKva — 400 KVA_NO_CHANGE")
    void 동일_kVA_거부() throws Exception {
        when(service.requestAdjustmentByLew(eq(APP_SEQ), eq(LEW_SEQ), any()))
                .thenThrow(new BusinessException(
                        "Proposed kVA is identical to current value",
                        HttpStatus.BAD_REQUEST, "KVA_NO_CHANGE"));

        mockMvc.perform(post("/api/lew/applications/" + APP_SEQ + "/kva-adjustment-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .principal(lewAuth())
                        .content(objectMapper.writeValueAsString(req(100, "no change reason"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("KVA_NO_CHANGE"));
    }

    @Test
    @DisplayName("master_prices 미존재 — 400 INVALID_KVA_TIER")
    void master_prices_없음() throws Exception {
        when(service.requestAdjustmentByLew(eq(APP_SEQ), eq(LEW_SEQ), any()))
                .thenThrow(new BusinessException(
                        "Invalid kVA tier: 999",
                        HttpStatus.BAD_REQUEST, "INVALID_KVA_TIER"));

        mockMvc.perform(post("/api/lew/applications/" + APP_SEQ + "/kva-adjustment-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .principal(lewAuth())
                        .content(objectMapper.writeValueAsString(req(999, "weird tier"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_KVA_TIER"));
    }

    @Test
    @DisplayName("유효성 — proposedKva 없으면 400")
    void 유효성_proposedKva_null() throws Exception {
        mockMvc.perform(post("/api/lew/applications/" + APP_SEQ + "/kva-adjustment-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .principal(lewAuth())
                        .content("{\"reason\":\"foo bar baz\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("유효성 — reason 비어 있으면 400")
    void 유효성_reason_blank() throws Exception {
        mockMvc.perform(post("/api/lew/applications/" + APP_SEQ + "/kva-adjustment-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .principal(lewAuth())
                        .content("{\"proposedKva\":200,\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}

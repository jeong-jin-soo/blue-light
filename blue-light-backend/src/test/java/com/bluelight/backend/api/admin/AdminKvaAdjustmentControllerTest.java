package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.KvaAdjustmentHistoryItem;
import com.bluelight.backend.api.admin.dto.KvaSettlementUpdateRequest;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.common.exception.GlobalExceptionHandler;
import com.bluelight.backend.domain.kva.AdminPaymentAdjustment;
import com.bluelight.backend.domain.kva.ChangedByRole;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PR-4 — {@link AdminKvaAdjustmentController} 의 PR-4 신규 엔드포인트 웹 레이어 테스트.
 *
 * <p>스펙: {@code doc/Project Analysis/kva-postpayment-adjustment-spec.md} §4.3 / PR-4.</p>
 *
 * <p>Standalone MockMvc — {@code @PreAuthorize} AOP 미작동. 본 테스트는 컨트롤러가 서비스에
 * 정확한 인자를 전달하고, 서비스 예외를 GlobalExceptionHandler 가 올바른 HTTP status + code 로
 * 매핑하는지 검증한다.</p>
 *
 * <h2>커버 시나리오</h2>
 * <ul>
 *   <li>GET 이력 정상 — 200 OK + 응답 payload (lewRequestSeq 그룹 포함)</li>
 *   <li>GET 이력 빈 배열 — 200 OK + []</li>
 *   <li>PATCH settlement 정상 — 200 OK + 갱신된 row</li>
 *   <li>PATCH settlement D6 거부 — 409 KVA_SETTLEMENT_ALREADY_FINALIZED</li>
 *   <li>PATCH settlement status 거부 — 409 KVA_SETTLEMENT_NOT_APPLICABLE</li>
 *   <li>PATCH settlement row 미존재 — 404 KVA_ADJUSTMENT_NOT_FOUND</li>
 *   <li>PATCH 유효성 — paymentAdjustment null → 400</li>
 * </ul>
 */
@DisplayName("AdminKvaAdjustmentController — PR-4")
class AdminKvaAdjustmentControllerTest {

    private static final long ADMIN_SEQ = 99L;
    private static final long APP_SEQ = 1L;
    private static final long ADJ_SEQ = 42L;

    private KvaPostPaymentService service;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private Authentication adminAuth() {
        return new UsernamePasswordAuthenticationToken(
                ADMIN_SEQ, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    @BeforeEach
    void setUp() {
        service = mock(KvaPostPaymentService.class);
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AdminKvaAdjustmentController(service))
                .setControllerAdvice(new GlobalExceptionHandler(mock(com.bluelight.backend.api.audit.AuditLogService.class)))
                .build();
        SecurityContextHolder.getContext().setAuthentication(adminAuth());
    }

    private KvaSettlementUpdateRequest settlementReq(AdminPaymentAdjustment pa,
                                                      BigDecimal amount,
                                                      String ref, String memo, Boolean notifyLew) {
        KvaSettlementUpdateRequest r = new KvaSettlementUpdateRequest();
        r.setPaymentAdjustment(pa);
        r.setSettledAmount(amount);
        r.setReceiptReferenceNumber(ref);
        r.setSettlementMemo(memo);
        r.setNotifyLew(notifyLew);
        return r;
    }

    @Test
    @DisplayName("GET 이력 정상 — 두 row 반환, lewRequestSeq 로 묶기 가능")
    void GET_이력_정상() throws Exception {
        // LEW 요청 row → ADMIN 변경 row 가 lewRequestSeq=10 으로 연결된 케이스.
        KvaAdjustmentHistoryItem adminRow = KvaAdjustmentHistoryItem.builder()
                .adjustmentSeq(11L)
                .status(KvaAdjustmentStatus.APPLIED)
                .changedByRole(ChangedByRole.ADMIN)
                .changedByUserName("Admin User")
                .previousKva(100).newKva(200)
                .previousQuoteAmount(new BigDecimal("450.00"))
                .newQuoteAmount(new BigDecimal("650.00"))
                .amountDifference(new BigDecimal("200.00"))
                .reason("Site survey accepted")
                .paymentAdjustment(AdminPaymentAdjustment.PENDING)
                .lewRequestSeq(10L)
                .createdAt(LocalDateTime.of(2026, 5, 1, 11, 0))
                .build();
        KvaAdjustmentHistoryItem lewRow = KvaAdjustmentHistoryItem.builder()
                .adjustmentSeq(10L)
                .status(KvaAdjustmentStatus.RESOLVED_BY_ADMIN_OVERRIDE)
                .changedByRole(ChangedByRole.LEW)
                .changedByUserName("Long Eric")
                .previousKva(100).proposedKva(200)
                .reason("Site survey: actual load 180 kVA")
                .createdAt(LocalDateTime.of(2026, 5, 1, 10, 0))
                .build();
        when(service.getAdjustmentHistory(eq(APP_SEQ)))
                .thenReturn(List.of(adminRow, lewRow));

        mockMvc.perform(get("/api/admin/applications/" + APP_SEQ + "/kva-adjustments")
                        .principal(adminAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].adjustmentSeq").value(11))
                .andExpect(jsonPath("$[0].changedByRole").value("ADMIN"))
                .andExpect(jsonPath("$[0].lewRequestSeq").value(10))
                .andExpect(jsonPath("$[0].status").value("APPLIED"))
                .andExpect(jsonPath("$[1].adjustmentSeq").value(10))
                .andExpect(jsonPath("$[1].changedByRole").value("LEW"))
                .andExpect(jsonPath("$[1].status").value("RESOLVED_BY_ADMIN_OVERRIDE"));
    }

    @Test
    @DisplayName("GET 이력 — 빈 배열도 200 OK")
    void GET_이력_빈배열() throws Exception {
        when(service.getAdjustmentHistory(eq(APP_SEQ))).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/applications/" + APP_SEQ + "/kva-adjustments")
                        .principal(adminAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("PATCH settlement 정상 — 200 OK + 갱신된 row 반환")
    void PATCH_settlement_정상() throws Exception {
        KvaAdjustmentHistoryItem updated = KvaAdjustmentHistoryItem.builder()
                .adjustmentSeq(ADJ_SEQ)
                .status(KvaAdjustmentStatus.APPLIED)
                .changedByRole(ChangedByRole.ADMIN)
                .paymentAdjustment(AdminPaymentAdjustment.PAID_DIFFERENCE)
                .settledAmount(new BigDecimal("200.00"))
                .receiptReferenceNumber("PAYNOW-ABC-123")
                .settlementMemo("Manual transfer 2026-05-02")
                .settledAt(LocalDateTime.of(2026, 5, 2, 14, 30))
                .build();
        when(service.markSettlement(eq(APP_SEQ), eq(ADJ_SEQ), any(), eq(ADMIN_SEQ)))
                .thenReturn(updated);

        mockMvc.perform(patch("/api/admin/applications/" + APP_SEQ + "/kva-adjustments/" + ADJ_SEQ + "/settlement")
                        .contentType(MediaType.APPLICATION_JSON)
                        .principal(adminAuth())
                        .content(objectMapper.writeValueAsString(
                                settlementReq(AdminPaymentAdjustment.PAID_DIFFERENCE,
                                        new BigDecimal("200.00"), "PAYNOW-ABC-123",
                                        "Manual transfer 2026-05-02", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adjustmentSeq").value(ADJ_SEQ))
                .andExpect(jsonPath("$.paymentAdjustment").value("PAID_DIFFERENCE"))
                .andExpect(jsonPath("$.settledAmount").value(200.00))
                .andExpect(jsonPath("$.receiptReferenceNumber").value("PAYNOW-ABC-123"));
    }

    @Test
    @DisplayName("PATCH settlement D6 거부 — 409 KVA_SETTLEMENT_ALREADY_FINALIZED")
    void PATCH_settlement_D6_거부() throws Exception {
        when(service.markSettlement(eq(APP_SEQ), eq(ADJ_SEQ), any(), eq(ADMIN_SEQ)))
                .thenThrow(new BusinessException(
                        "Settlement is already finalized — create a new adjustment record to correct",
                        HttpStatus.CONFLICT, "KVA_SETTLEMENT_ALREADY_FINALIZED"));

        mockMvc.perform(patch("/api/admin/applications/" + APP_SEQ + "/kva-adjustments/" + ADJ_SEQ + "/settlement")
                        .contentType(MediaType.APPLICATION_JSON)
                        .principal(adminAuth())
                        .content(objectMapper.writeValueAsString(
                                settlementReq(AdminPaymentAdjustment.REFUNDED,
                                        new BigDecimal("100.00"), null, null, true))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KVA_SETTLEMENT_ALREADY_FINALIZED"));
    }

    @Test
    @DisplayName("PATCH settlement 잘못된 status 거부 — 409 KVA_SETTLEMENT_NOT_APPLICABLE")
    void PATCH_settlement_status_거부() throws Exception {
        when(service.markSettlement(eq(APP_SEQ), eq(ADJ_SEQ), any(), eq(ADMIN_SEQ)))
                .thenThrow(new BusinessException(
                        "Settlement is only applicable to APPLIED or RESOLVED_BY_ADMIN_OVERRIDE rows",
                        HttpStatus.CONFLICT, "KVA_SETTLEMENT_NOT_APPLICABLE"));

        mockMvc.perform(patch("/api/admin/applications/" + APP_SEQ + "/kva-adjustments/" + ADJ_SEQ + "/settlement")
                        .contentType(MediaType.APPLICATION_JSON)
                        .principal(adminAuth())
                        .content(objectMapper.writeValueAsString(
                                settlementReq(AdminPaymentAdjustment.WAIVED,
                                        null, null, null, true))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KVA_SETTLEMENT_NOT_APPLICABLE"));
    }

    @Test
    @DisplayName("PATCH settlement row 미존재 — 404 KVA_ADJUSTMENT_NOT_FOUND")
    void PATCH_settlement_row_미존재() throws Exception {
        when(service.markSettlement(eq(APP_SEQ), eq(ADJ_SEQ), any(), eq(ADMIN_SEQ)))
                .thenThrow(new BusinessException(
                        "Adjustment record not found",
                        HttpStatus.NOT_FOUND, "KVA_ADJUSTMENT_NOT_FOUND"));

        mockMvc.perform(patch("/api/admin/applications/" + APP_SEQ + "/kva-adjustments/" + ADJ_SEQ + "/settlement")
                        .contentType(MediaType.APPLICATION_JSON)
                        .principal(adminAuth())
                        .content(objectMapper.writeValueAsString(
                                settlementReq(AdminPaymentAdjustment.PAID_DIFFERENCE,
                                        new BigDecimal("100.00"), null, null, true))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KVA_ADJUSTMENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("PATCH 유효성 — paymentAdjustment 누락 시 400")
    void PATCH_유효성_paymentAdjustment_null() throws Exception {
        mockMvc.perform(patch("/api/admin/applications/" + APP_SEQ + "/kva-adjustments/" + ADJ_SEQ + "/settlement")
                        .contentType(MediaType.APPLICATION_JSON)
                        .principal(adminAuth())
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}

package com.bluelight.backend.api.admin.manualemail;

import com.bluelight.backend.api.admin.manualemail.dto.ManualEmailDispatchHistoryItem;
import com.bluelight.backend.api.admin.manualemail.dto.ManualEmailDispatchResponse;
import com.bluelight.backend.api.admin.manualemail.dto.ManualEmailPreviewResponse;
import com.bluelight.backend.api.admin.manualemail.dto.SendManualEmailRequest;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.common.exception.GlobalExceptionHandler;
import com.bluelight.backend.domain.manualemail.BodyFormat;
import com.bluelight.backend.domain.manualemail.DispatchStatus;
import com.bluelight.backend.domain.manualemail.RecipientType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PR-1 — {@link AdminManualEmailController} 웹 레이어 테스트.
 *
 * <p>스펙: {@code doc/Project Analysis/admin-manual-email-spec.md} §6 (AC-A1, AC-A5, AC-A6, AC-A9, AC-A10).</p>
 *
 * <p>Standalone MockMvc — {@code @PreAuthorize} AOP 미작동. 본 테스트는 컨트롤러가 서비스에 정확한
 * 인자를 전달하고, validation 실패와 BusinessException 이 올바른 HTTP status 로 매핑되는지 검증.</p>
 */
@DisplayName("AdminManualEmailController — PR-1")
class AdminManualEmailControllerTest {

    private static final long ADMIN_SEQ = 99L;

    private ManualEmailDispatcher dispatcher;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private Authentication adminAuth() {
        return new UsernamePasswordAuthenticationToken(
                ADMIN_SEQ, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    @BeforeEach
    void setUp() {
        dispatcher = mock(ManualEmailDispatcher.class);
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AdminManualEmailController(dispatcher))
                .setControllerAdvice(new GlobalExceptionHandler(mock(com.bluelight.backend.api.audit.AuditLogService.class)))
                .build();
        SecurityContextHolder.getContext().setAuthentication(adminAuth());
    }

    private SendManualEmailRequest applicantReq() {
        SendManualEmailRequest r = new SendManualEmailRequest();
        r.setRecipientType(RecipientType.APPLICANT);
        r.setRecipientUserSeq(12L);
        r.setSubject("Hello");
        r.setBodyText("Body");
        return r;
    }

    @Test
    @DisplayName("AC-A1 POST 정상 — 200 OK + dispatchSeq + status PENDING")
    void POST_정상() throws Exception {
        when(dispatcher.dispatch(any(SendManualEmailRequest.class), eq(ADMIN_SEQ)))
                .thenReturn(ManualEmailDispatchResponse.builder()
                        .dispatchSeq(123L)
                        .dispatchStatus(DispatchStatus.PENDING)
                        .sentCount(0)
                        .failedCount(0)
                        .build());

        mockMvc.perform(post("/api/admin/manual-emails")
                        .contentType(MediaType.APPLICATION_JSON)
                        .principal(adminAuth())
                        .content(objectMapper.writeValueAsString(applicantReq())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dispatchSeq").value(123))
                .andExpect(jsonPath("$.dispatchStatus").value("PENDING"))
                .andExpect(jsonPath("$.sentCount").value(0));
    }

    @Test
    @DisplayName("AC-A5 POST validation — subject 빈 문자열 → 400")
    void POST_validation_subject_blank() throws Exception {
        SendManualEmailRequest req = applicantReq();
        req.setSubject("");
        mockMvc.perform(post("/api/admin/manual-emails")
                        .contentType(MediaType.APPLICATION_JSON)
                        .principal(adminAuth())
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AC-A5 POST validation — bodyText 빈 문자열 → 400")
    void POST_validation_body_blank() throws Exception {
        SendManualEmailRequest req = applicantReq();
        req.setBodyText("");
        mockMvc.perform(post("/api/admin/manual-emails")
                        .contentType(MediaType.APPLICATION_JSON)
                        .principal(adminAuth())
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AC-A6 POST validation — subject 201자 → 400")
    void POST_validation_subject_overflow() throws Exception {
        SendManualEmailRequest req = applicantReq();
        req.setSubject("a".repeat(201));
        mockMvc.perform(post("/api/admin/manual-emails")
                        .contentType(MediaType.APPLICATION_JSON)
                        .principal(adminAuth())
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AC-A6 POST validation — bodyText 50,001자 → 400")
    void POST_validation_body_overflow() throws Exception {
        SendManualEmailRequest req = applicantReq();
        req.setBodyText("a".repeat(50_001));
        mockMvc.perform(post("/api/admin/manual-emails")
                        .contentType(MediaType.APPLICATION_JSON)
                        .principal(adminAuth())
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("EXTERNAL — 잘못된 이메일 형식 → 400")
    void POST_validation_external_email_format() throws Exception {
        SendManualEmailRequest req = new SendManualEmailRequest();
        req.setRecipientType(RecipientType.EXTERNAL);
        req.setRecipientEmail("not-an-email");
        req.setSubject("S");
        req.setBodyText("B");
        mockMvc.perform(post("/api/admin/manual-emails")
                        .contentType(MediaType.APPLICATION_JSON)
                        .principal(adminAuth())
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PR-2: MULTI 1건 이하 → 400 MULTI_REQUIRES_AT_LEAST_TWO_RECIPIENTS")
    void POST_MULTI_1건_거부() throws Exception {
        SendManualEmailRequest req = applicantReq();
        req.setRecipientType(RecipientType.MULTI);
        when(dispatcher.dispatch(any(SendManualEmailRequest.class), eq(ADMIN_SEQ)))
                .thenThrow(new BusinessException(
                        "MULTI dispatch requires at least 2 recipients",
                        HttpStatus.BAD_REQUEST, "MULTI_REQUIRES_AT_LEAST_TWO_RECIPIENTS"));

        mockMvc.perform(post("/api/admin/manual-emails")
                        .contentType(MediaType.APPLICATION_JSON)
                        .principal(adminAuth())
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MULTI_REQUIRES_AT_LEAST_TWO_RECIPIENTS"));
    }

    @Test
    @DisplayName("AC-A9 멱등성 충돌 — 409 MANUAL_EMAIL_DUPLICATE_SUSPECTED")
    void POST_멱등성_충돌() throws Exception {
        when(dispatcher.dispatch(any(SendManualEmailRequest.class), eq(ADMIN_SEQ)))
                .thenThrow(new BusinessException(
                        "Duplicate dispatch detected within 30 seconds.",
                        HttpStatus.CONFLICT, "MANUAL_EMAIL_DUPLICATE_SUSPECTED"));

        mockMvc.perform(post("/api/admin/manual-emails")
                        .contentType(MediaType.APPLICATION_JSON)
                        .principal(adminAuth())
                        .content(objectMapper.writeValueAsString(applicantReq())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MANUAL_EMAIL_DUPLICATE_SUSPECTED"));
    }

    @Test
    @DisplayName("AC-A10 GET 이력 — 빈 페이지도 200 OK")
    void GET_history_empty() throws Exception {
        when(dispatcher.getDispatchHistory(any(), any()))
                .thenReturn(Page.empty(PageRequest.of(0, 20)));

        mockMvc.perform(get("/api/admin/manual-emails")
                        .principal(adminAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("AC-A10 GET 이력 — 1건 응답 + 필드 매핑")
    void GET_history_one() throws Exception {
        ManualEmailDispatchHistoryItem item = ManualEmailDispatchHistoryItem.builder()
                .dispatchSeq(123L)
                .senderUserSeq(ADMIN_SEQ)
                .recipientType(RecipientType.APPLICANT)
                .recipientUserSeq(12L)
                .recipientEmail("alice@example.com")
                .subject("Hello")
                .bodyText("Body")
                .bodyFormat(BodyFormat.PLAIN_TEXT)
                .dispatchStatus(DispatchStatus.SENT)
                .sentCount(1)
                .failedCount(0)
                .dispatchedAt(LocalDateTime.of(2026, 5, 1, 10, 0))
                .createdAt(LocalDateTime.of(2026, 5, 1, 10, 0))
                .build();
        when(dispatcher.getDispatchHistory(any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        List.of(item), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/admin/manual-emails")
                        .principal(adminAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].dispatchSeq").value(123))
                .andExpect(jsonPath("$.content[0].dispatchStatus").value("SENT"))
                .andExpect(jsonPath("$.content[0].recipientType").value("APPLICANT"));
    }

    @Test
    @DisplayName("GET 단건 상세 — 200 OK")
    void GET_detail_정상() throws Exception {
        ManualEmailDispatchHistoryItem item = ManualEmailDispatchHistoryItem.builder()
                .dispatchSeq(123L)
                .senderUserSeq(ADMIN_SEQ)
                .recipientType(RecipientType.EXTERNAL)
                .recipientEmail("partner@spgroup.com.sg")
                .subject("Coordination")
                .bodyText("Following up.")
                .bodyFormat(BodyFormat.PLAIN_TEXT)
                .dispatchStatus(DispatchStatus.SENT)
                .sentCount(1)
                .failedCount(0)
                .build();
        when(dispatcher.getDispatchDetail(eq(123L))).thenReturn(item);

        mockMvc.perform(get("/api/admin/manual-emails/123")
                        .principal(adminAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dispatchSeq").value(123))
                .andExpect(jsonPath("$.recipientType").value("EXTERNAL"))
                .andExpect(jsonPath("$.recipientEmail").value("partner@spgroup.com.sg"));
    }

    @Test
    @DisplayName("GET 단건 상세 — 미존재 시 404 MANUAL_EMAIL_DISPATCH_NOT_FOUND")
    void GET_detail_미존재() throws Exception {
        when(dispatcher.getDispatchDetail(eq(999L))).thenThrow(new BusinessException(
                "Manual email dispatch #999 not found",
                HttpStatus.NOT_FOUND, "MANUAL_EMAIL_DISPATCH_NOT_FOUND"));

        mockMvc.perform(get("/api/admin/manual-emails/999")
                        .principal(adminAuth()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MANUAL_EMAIL_DISPATCH_NOT_FOUND"));
    }

    @Test
    @DisplayName("PR-3 POST /preview 정상 — 200 OK + renderedSubject + renderedHtmlPreview")
    void POST_preview_정상() throws Exception {
        when(dispatcher.preview(any(SendManualEmailRequest.class), eq(ADMIN_SEQ)))
                .thenReturn(ManualEmailPreviewResponse.of("Hi", "<html>OK</html>"));

        SendManualEmailRequest req = applicantReq();

        mockMvc.perform(post("/api/admin/manual-emails/preview")
                        .principal(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renderedSubject").value("Hi"))
                .andExpect(jsonPath("$.renderedHtmlPreview").value("<html>OK</html>"));
    }

    @Test
    @DisplayName("PR-3 POST /preview validation 실패 — subject 누락 400 VALIDATION_ERROR")
    void POST_preview_subject누락_400() throws Exception {
        SendManualEmailRequest req = applicantReq();
        req.setSubject("");

        mockMvc.perform(post("/api/admin/manual-emails/preview")
                        .principal(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ── PR-4 Quota / Category suggestions ────────────────────────

    @Test
    @DisplayName("PR-4 GET /quota — { dailyCap, usedToday, remaining }")
    void GET_quota_정상() throws Exception {
        when(dispatcher.getQuotaSnapshot(eq(ADMIN_SEQ)))
                .thenReturn(new ManualEmailDispatcher.QuotaSnapshot(100, 32, 68));

        mockMvc.perform(get("/api/admin/manual-emails/quota")
                        .principal(adminAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyCap").value(100))
                .andExpect(jsonPath("$.usedToday").value(32))
                .andExpect(jsonPath("$.remaining").value(68));
    }

    @Test
    @DisplayName("PR-4 GET /category-suggestions — { suggestions: [...] }")
    void GET_categorySuggestions_정상() throws Exception {
        when(dispatcher.getCategorySuggestions())
                .thenReturn(List.of("PAYMENT_NOTICE", "MAINTENANCE", "INFO", "MISC"));

        mockMvc.perform(get("/api/admin/manual-emails/category-suggestions")
                        .principal(adminAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestions").isArray())
                .andExpect(jsonPath("$.suggestions.length()").value(4))
                .andExpect(jsonPath("$.suggestions[0]").value("PAYMENT_NOTICE"));
    }

    @Test
    @DisplayName("PR-4 POST 발송 — alsoCreateInAppNotification 필드가 디스패처에 그대로 전달")
    void POST_alsoCreateInAppNotification_passthrough() throws Exception {
        when(dispatcher.dispatch(any(SendManualEmailRequest.class), eq(ADMIN_SEQ)))
                .thenReturn(ManualEmailDispatchResponse.builder()
                        .dispatchSeq(456L)
                        .dispatchStatus(DispatchStatus.PENDING)
                        .sentCount(0)
                        .failedCount(0)
                        .build());

        SendManualEmailRequest req = applicantReq();
        req.setAlsoCreateInAppNotification(false);

        mockMvc.perform(post("/api/admin/manual-emails")
                        .contentType(MediaType.APPLICATION_JSON)
                        .principal(adminAuth())
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        // dispatcher 가 받은 DTO 의 alsoCreateInAppNotification 필드 검증.
        org.mockito.ArgumentCaptor<SendManualEmailRequest> captor =
                org.mockito.ArgumentCaptor.forClass(SendManualEmailRequest.class);
        org.mockito.Mockito.verify(dispatcher).dispatch(captor.capture(), eq(ADMIN_SEQ));
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getAlsoCreateInAppNotification())
                .isFalse();
    }

    @Test
    @DisplayName("PR-4 POST 발송 — daily cap 초과 시 429 MANUAL_EMAIL_DAILY_CAP_EXCEEDED 매핑")
    void POST_dailyCapExceeded_429() throws Exception {
        when(dispatcher.dispatch(any(SendManualEmailRequest.class), eq(ADMIN_SEQ)))
                .thenThrow(new BusinessException(
                        "Daily limit of 100 manual email recipients reached",
                        HttpStatus.TOO_MANY_REQUESTS,
                        "MANUAL_EMAIL_DAILY_CAP_EXCEEDED"));

        mockMvc.perform(post("/api/admin/manual-emails")
                        .contentType(MediaType.APPLICATION_JSON)
                        .principal(adminAuth())
                        .content(objectMapper.writeValueAsString(applicantReq())))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("MANUAL_EMAIL_DAILY_CAP_EXCEEDED"));
    }
}

package com.bluelight.backend.api.admin.notification.template;

import com.bluelight.backend.api.admin.notification.template.dto.CreateDraftRequest;
import com.bluelight.backend.api.admin.notification.template.dto.DisableTemplateRequest;
import com.bluelight.backend.api.admin.notification.template.dto.ReviewDraftRequest;
import com.bluelight.backend.api.admin.notification.template.dto.UpdateDraftRequest;
import com.bluelight.backend.api.notification.template.lint.LintIssue;
import com.bluelight.backend.api.notification.template.lint.LintResult;
import com.bluelight.backend.api.notification.template.lint.TemplateLintException;
import com.bluelight.backend.domain.notification.NotificationCategory;
import com.bluelight.backend.domain.notification.NotificationChannel;
import com.bluelight.backend.domain.notification.NotificationSeverity;
import com.bluelight.backend.domain.notification.NotificationTemplate;
import com.bluelight.backend.domain.notification.NotificationTemplateDraft;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AdminNotificationTemplateController — PR-T3 WebMvc 테스트.
 *
 * <p>Standalone MockMvc — {@code @PreAuthorize} AOP 미작동. 본 테스트는 컨트롤러가 서비스에
 * 정확한 인자를 전달하고, TemplateAdminExceptionHandler 가 도메인 예외를 올바른 HTTP 상태로
 * 매핑하는지 검증한다. 권한 게이트 자체는 Service 단위 테스트(NotificationTemplateAdminServiceTest)
 * 의 H-S3 케이스 + Integration 테스트(별도 PR)로 검증.</p>
 */
@DisplayName("AdminNotificationTemplateController - PR-T3")
class AdminNotificationTemplateControllerTest {

    private NotificationTemplateAdminService adminService;
    private DraftReviewService reviewService;
    private TemplatePreviewService previewService;
    private TemplateTestSendService testSendService;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private Authentication nmAuth() {
        return new UsernamePasswordAuthenticationToken(
                1001L, null, List.of(new SimpleGrantedAuthority("ROLE_NOTIFICATION_MANAGER")));
    }

    private Authentication saAuth() {
        return new UsernamePasswordAuthenticationToken(
                9001L, null, List.of(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")));
    }

    private Authentication lewAuth() {
        return new UsernamePasswordAuthenticationToken(
                7001L, null, List.of(new SimpleGrantedAuthority("ROLE_LEW")));
    }

    @BeforeEach
    void setUp() {
        adminService = mock(NotificationTemplateAdminService.class);
        reviewService = mock(DraftReviewService.class);
        previewService = mock(TemplatePreviewService.class);
        testSendService = mock(TemplateTestSendService.class);
        objectMapper = new ObjectMapper();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AdminNotificationTemplateController(
                        adminService, reviewService, previewService, testSendService))
                .setControllerAdvice(new TemplateAdminExceptionHandler())
                .build();
    }

    private NotificationTemplate buildTemplate(NotificationCategory cat, String recipientRoles, boolean enabled) {
        return NotificationTemplate.builder()
                .templateCode("A-17")
                .channel(NotificationChannel.EMAIL)
                .locale("en")
                .subject("[LicenseKaki] Payment requested")
                .bodyText("Hi {{applicantName}}. {{footerBlock}}")
                .variablesJson("[\"applicantName\"]")
                .enabled(enabled)
                .catalogMetaKey("A-17")
                .category(cat)
                .severity(NotificationSeverity.CRITICAL)
                .recipientRoles(recipientRoles)
                .build();
    }

    private NotificationTemplateDraft buildDraft() {
        return NotificationTemplateDraft.builder()
                .templateSeq(42L)
                .templateCode("A-17")
                .channel(NotificationChannel.EMAIL)
                .locale("en")
                .subject("subj")
                .bodyText("Hi {{applicantName}}. {{footerBlock}}")
                .variablesJson("[\"applicantName\"]")
                .category(NotificationCategory.PAYMENT)
                .severity(NotificationSeverity.CRITICAL)
                .recipientRoles("APPLICANT")
                .submittedBy(1001L)
                .submissionNote("법무 반영")
                .build();
    }

    // ============================================================
    // GET list / detail
    // ============================================================

    @Test
    @DisplayName("GET list - NM 호출 시 모든 row, recipient_roles 필터 적용 안 함")
    void listTemplates_nmGetsAllRows() throws Exception {
        // PageImpl(1-arg) 은 Unpaged 사용 → Jackson 직렬화 시 UnsupportedOperation. 3-arg 사용.
        List<NotificationTemplate> content = List.of(buildTemplate(NotificationCategory.PAYMENT, "APPLICANT", true));
        when(adminService.searchTemplates(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(content, PageRequest.of(0, 50), content.size()));

        mockMvc.perform(get("/api/admin/notification-templates").principal(nmAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].templateCode").value("A-17"))
                .andExpect(jsonPath("$.content[0].category").value("PAYMENT"));

        // NM 은 role filter 없이 전체 조회
        verify(adminService).searchTemplates(any(), any(), any(), any(), any(), eq(null), any());
    }

    @Test
    @DisplayName("GET list - LEW(D-5) 호출 시 본인 역할 필터 자동 적용")
    void listTemplates_lewFiltersByRecipientRole() throws Exception {
        when(adminService.searchTemplates(any(), any(), any(), any(), any(), eq("LEW"), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        mockMvc.perform(get("/api/admin/notification-templates").principal(lewAuth()))
                .andExpect(status().isOk());

        verify(adminService).searchTemplates(any(), any(), any(), any(), any(), eq("LEW"), any());
    }

    @Test
    @DisplayName("GET detail - 200 + ETag 헤더 (version)")
    void getTemplate_returnsETag() throws Exception {
        NotificationTemplate t = buildTemplate(NotificationCategory.STATUS, "APPLICANT", true);
        when(adminService.findTemplate(42L)).thenReturn(java.util.Optional.of(t));

        mockMvc.perform(get("/api/admin/notification-templates/42").principal(nmAuth()))
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"));
    }

    @Test
    @DisplayName("GET detail - 미존재 → 404 TEMPLATE_NOT_FOUND")
    void getTemplate_notFound() throws Exception {
        when(adminService.findTemplate(999L)).thenReturn(java.util.Optional.empty());

        mockMvc.perform(get("/api/admin/notification-templates/999").principal(nmAuth()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TEMPLATE_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET detail - D-5 권한 위반 (LEW 가 APPLICANT 수신 템플릿 접근) → 404 (정보 비누설)")
    void getTemplate_lewCannotAccessApplicantOnlyTemplate() throws Exception {
        NotificationTemplate t = buildTemplate(NotificationCategory.STATUS, "APPLICANT", true);
        when(adminService.findTemplate(42L)).thenReturn(java.util.Optional.of(t));

        mockMvc.perform(get("/api/admin/notification-templates/42").principal(lewAuth()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TEMPLATE_NOT_FOUND"));
    }

    // ============================================================
    // POST create draft
    // ============================================================

    @Test
    @DisplayName("POST drafts - 201 + body (NM 작성 성공)")
    void createDraft_success() throws Exception {
        when(adminService.createDraft(any(), eq(1001L))).thenReturn(buildDraft());

        CreateDraftRequest req = new CreateDraftRequest(
                42L, "A-17", NotificationChannel.EMAIL, "en",
                "subj", "Hi {{applicantName}}. {{footerBlock}}",
                "[\"applicantName\"]", null,
                NotificationCategory.PAYMENT, NotificationSeverity.CRITICAL,
                "APPLICANT", "법무 반영"
        );

        mockMvc.perform(post("/api/admin/notification-templates/drafts")
                        .principal(nmAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.templateCode").value("A-17"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("POST drafts - Lint 실패 → 400 + TEMPLATE_LINT_FAILED + lint body")
    void createDraft_lintFailure() throws Exception {
        LintResult lintResult = new LintResult();
        lintResult.add(LintIssue.error("L1_VARIABLE_WHITELIST",
                "미정의 변수: unknownVar", "body", "unknownVar"));
        when(adminService.createDraft(any(), anyLong())).thenThrow(new TemplateLintException(lintResult));

        CreateDraftRequest req = new CreateDraftRequest(
                42L, "A-17", NotificationChannel.EMAIL, "en",
                "subj", "Hi {{unknownVar}}",
                "[]", null,
                NotificationCategory.STATUS, NotificationSeverity.IMPORTANT,
                "APPLICANT", null
        );

        mockMvc.perform(post("/api/admin/notification-templates/drafts")
                        .principal(nmAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TEMPLATE_LINT_FAILED"))
                .andExpect(jsonPath("$.lint.errors[0].ruleCode").value("L1_VARIABLE_WHITELIST"))
                .andExpect(jsonPath("$.lint.errors[0].message").value(org.hamcrest.Matchers.containsString("unknownVar")));
    }

    // ============================================================
    // PATCH edit draft
    // ============================================================

    @Test
    @DisplayName("PATCH drafts/{seq} - 작성자 본인 아니면 403 DRAFT_NOT_OWNED")
    void editDraft_ownershipForbidden() throws Exception {
        when(adminService.findDraft(7L)).thenReturn(java.util.Optional.of(buildDraft()));
        doThrow(new NotificationTemplateAdminService.DraftOwnershipException(7L, 9999L))
                .when(adminService).editDraft(eq(7L), any(), eq(9999L));

        UpdateDraftRequest req = new UpdateDraftRequest(
                "new subj", "new body. {{footerBlock}}", "[]", null,
                NotificationCategory.STATUS, NotificationSeverity.IMPORTANT,
                "APPLICANT", null);

        mockMvc.perform(patch("/api/admin/notification-templates/drafts/7")
                        .principal(new UsernamePasswordAuthenticationToken(9999L, null,
                                List.of(new SimpleGrantedAuthority("ROLE_NOTIFICATION_MANAGER"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DRAFT_NOT_OWNED"));
    }

    // ============================================================
    // Approve / Reject
    // ============================================================

    @Test
    @DisplayName("POST approve - SA 호출 시 200 + 갱신된 template")
    void approveDraft_success() throws Exception {
        when(reviewService.approve(eq(7L), eq(9001L), eq("LGTM"), any()))
                .thenReturn(buildTemplate(NotificationCategory.PAYMENT, "APPLICANT", true));

        ReviewDraftRequest req = new ReviewDraftRequest("LGTM");

        mockMvc.perform(post("/api/admin/notification-templates/drafts/7/approve")
                        .principal(saAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templateCode").value("A-17"));
    }

    @Test
    @DisplayName("POST approve - PAYMENT 카테고리 reviewNote 누락 → 400 CHANGE_REASON_REQUIRED")
    void approveDraft_paymentRequiresNote() throws Exception {
        when(reviewService.approve(eq(7L), anyLong(), eq(null), any()))
                .thenThrow(new NotificationTemplateAdminService.ChangeReasonRequiredException(NotificationCategory.PAYMENT));

        mockMvc.perform(post("/api/admin/notification-templates/drafts/7/approve")
                        .principal(saAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHANGE_REASON_REQUIRED"));
    }

    @Test
    @DisplayName("POST reject - reviewNote 빈 문자열 → 400 INVALID_INPUT (엔티티 가드)")
    void rejectDraft_blankNoteRejected() throws Exception {
        doThrow(new IllegalArgumentException("reject 시 reviewNote 는 필수입니다."))
                .when(reviewService).reject(eq(7L), anyLong(), eq(""));

        ReviewDraftRequest req = new ReviewDraftRequest("");

        mockMvc.perform(post("/api/admin/notification-templates/drafts/7/reject")
                        .principal(saAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    // ============================================================
    // Enable / Disable (H-S3)
    // ============================================================

    @Test
    @DisplayName("POST disable - PAYMENT reason 누락 → 400 CHANGE_REASON_REQUIRED")
    void disable_paymentRequiresReason() throws Exception {
        doThrow(new NotificationTemplateAdminService.ChangeReasonRequiredException(NotificationCategory.PAYMENT))
                .when(adminService).disableTemplate(eq(42L), eq(""), anyLong(), any(), anyBoolean());

        DisableTemplateRequest req = new DisableTemplateRequest("");

        mockMvc.perform(post("/api/admin/notification-templates/42/disable")
                        .principal(saAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHANGE_REASON_REQUIRED"));
    }

    @Test
    @DisplayName("POST disable - SECURITY + NM 호출 → 403 SECURITY_CATEGORY_DISABLE_REQUIRES_SYSADMIN (H-S3)")
    void disable_securityNmForbidden() throws Exception {
        doThrow(new NotificationTemplateAdminService.SecurityCategoryDisableNotPermittedException(
                "A-04", NotificationCategory.SECURITY))
                .when(adminService).disableTemplate(eq(42L), any(), eq(1001L), any(), eq(false));

        DisableTemplateRequest req = new DisableTemplateRequest(
                "긴급 점검 — 50자 이상의 상세 사유를 작성합니다. 실제로는 더 상세한 내용이 들어갑니다.");

        mockMvc.perform(post("/api/admin/notification-templates/42/disable")
                        .principal(nmAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SECURITY_CATEGORY_DISABLE_REQUIRES_SYSADMIN"));
    }

    @Test
    @DisplayName("POST disable - SECURITY + SA + reason 50+자 → 204 + isSystemAdmin=true 전달")
    void disable_securitySaWithReasonSucceeds() throws Exception {
        DisableTemplateRequest req = new DisableTemplateRequest(
                "보안팀 점검 — A-04 비번 변경 통보 임시 차단 (티켓 SEC-1234, ETA 30분, 사후 보고 예정)");

        mockMvc.perform(post("/api/admin/notification-templates/42/disable")
                        .principal(saAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());

        verify(adminService).disableTemplate(eq(42L), any(), eq(9001L), any(), eq(true));
    }

    @Test
    @DisplayName("POST withdraw - 204 + 본인 actor 전달")
    void withdrawDraft_passesActor() throws Exception {
        mockMvc.perform(post("/api/admin/notification-templates/drafts/7/withdraw")
                        .principal(nmAuth()))
                .andExpect(status().isNoContent());

        verify(adminService).withdrawDraft(7L, 1001L);
        verify(reviewService, never()).approve(anyLong(), anyLong(), any(), any());
    }

    // ============================================================
    // PR-T4 — Preview + Test-send
    // ============================================================

    @Test
    @DisplayName("POST preview - 렌더된 subject/body + warnings 반환")
    void preview_returnsRendered() throws Exception {
        com.bluelight.backend.api.admin.notification.template.dto.TemplatePreviewResponse resp =
                new com.bluelight.backend.api.admin.notification.template.dto.TemplatePreviewResponse(
                        "[LicenseKaki] Payment requested",
                        "Hi Tan Ah Kow, please pay SGD 185.00.",
                        38,
                        null,
                        List.of(),
                        List.of()
                );
        when(previewService.preview(eq(42L), any())).thenReturn(resp);

        String body = "{\"payload\":{\"applicantName\":\"Tan Ah Kow\",\"amount\":\"185.00\"}}";

        mockMvc.perform(post("/api/admin/notification-templates/42/preview")
                        .principal(nmAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("[LicenseKaki] Payment requested"))
                .andExpect(jsonPath("$.body").value("Hi Tan Ah Kow, please pay SGD 185.00."))
                .andExpect(jsonPath("$.charCount").value(38));
    }

    @Test
    @DisplayName("POST test-send - 200 + outboxSeq + dailyQuota 정보")
    void testSend_returnsOutboxSeq() throws Exception {
        com.bluelight.backend.api.admin.notification.template.dto.TemplateTestSendResponse resp =
                new com.bluelight.backend.api.admin.notification.template.dto.TemplateTestSendResponse(
                        9001L, 3, 50);
        when(testSendService.sendTestToSelf(eq(42L), eq(1001L), any())).thenReturn(resp);

        mockMvc.perform(post("/api/admin/notification-templates/42/test-send")
                        .principal(nmAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outboxSeq").value(9001))
                .andExpect(jsonPath("$.dailyQuotaUsed").value(3))
                .andExpect(jsonPath("$.dailyQuotaMax").value(50));
    }

    @Test
    @DisplayName("POST test-send - 일일 quota 초과 → 429 TEST_SEND_QUOTA_EXCEEDED")
    void testSend_quotaExceeded() throws Exception {
        when(testSendService.sendTestToSelf(eq(42L), eq(1001L), any()))
                .thenThrow(new TestSendQuotaTracker.QuotaExceededException(1001L, 50));

        mockMvc.perform(post("/api/admin/notification-templates/42/test-send")
                        .principal(nmAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":{}}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TEST_SEND_QUOTA_EXCEEDED"));
    }

    @Test
    @DisplayName("POST test-send - SMS 채널 시도 시 400 UNSUPPORTED_TEST_CHANNEL")
    void testSend_unsupportedChannel() throws Exception {
        when(testSendService.sendTestToSelf(eq(42L), eq(1001L), any()))
                .thenThrow(new TemplateTestSendService.UnsupportedTestChannelException(NotificationChannel.SMS));

        mockMvc.perform(post("/api/admin/notification-templates/42/test-send")
                        .principal(nmAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_TEST_CHANNEL"));
    }
}

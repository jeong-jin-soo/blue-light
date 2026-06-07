package com.bluelight.backend.api.admin.notification.template;

import com.bluelight.backend.api.notification.template.lint.TemplateLintException;
import com.bluelight.backend.api.notification.template.lint.TemplateLinter;
import com.bluelight.backend.api.notification.template.lint.TemplateVariableValidator;
import com.bluelight.backend.domain.notification.NotificationCatalogRepository;
import com.bluelight.backend.domain.notification.NotificationCategory;
import com.bluelight.backend.domain.notification.NotificationChannel;
import com.bluelight.backend.domain.notification.NotificationSeverity;
import com.bluelight.backend.domain.notification.NotificationTemplate;
import com.bluelight.backend.domain.notification.NotificationTemplateDraft;
import com.bluelight.backend.domain.notification.NotificationTemplateDraftRepository;
import com.bluelight.backend.domain.notification.NotificationTemplateHistory;
import com.bluelight.backend.domain.notification.NotificationTemplateHistoryRepository;
import com.bluelight.backend.domain.notification.NotificationTemplateRepository;
import com.bluelight.backend.domain.notification.TemplateChangeType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NotificationTemplateAdminService — Draft CRUD + enable/disable + H-S3 SECURITY 가드 검증 (PR-T2).
 */
@DisplayName("NotificationTemplateAdminService - PR-T2")
class NotificationTemplateAdminServiceTest {

    private NotificationTemplateRepository templateRepository;
    private NotificationTemplateDraftRepository draftRepository;
    private NotificationTemplateHistoryRepository historyRepository;
    private NotificationCatalogRepository catalogRepository;
    private ApplicationEventPublisher eventPublisher;
    private NotificationTemplateAdminService service;

    @BeforeEach
    void setUp() {
        templateRepository = mock(NotificationTemplateRepository.class);
        draftRepository = mock(NotificationTemplateDraftRepository.class);
        historyRepository = mock(NotificationTemplateHistoryRepository.class);
        catalogRepository = mock(NotificationCatalogRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        ObjectMapper objectMapper = new ObjectMapper();
        TemplateVariableValidator validator = new TemplateVariableValidator(objectMapper);
        TemplateLinter linter = new TemplateLinter(validator);
        TemplateSnapshotMapper snapshotMapper = new TemplateSnapshotMapper(objectMapper);

        service = new NotificationTemplateAdminService(
                templateRepository, draftRepository, historyRepository, catalogRepository,
                linter, snapshotMapper, eventPublisher
        );

        when(draftRepository.save(any(NotificationTemplateDraft.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private NotificationTemplateAdminService.DraftMutationInput cleanInput() {
        return new NotificationTemplateAdminService.DraftMutationInput(
                42L, "A-17", NotificationChannel.EMAIL, "en",
                "[LicenseKaki] Payment requested",
                "Hi {{applicantName}}, please pay SGD {{amount}}. {{footerBlock}}",
                "[\"applicantName\",\"amount\"]",
                null, NotificationCategory.PAYMENT, NotificationSeverity.CRITICAL,
                "APPLICANT", "법무 요청 반영"
        );
    }

    private NotificationTemplate buildTemplate(long seq, NotificationCategory cat, boolean enabled) {
        NotificationTemplate t = NotificationTemplate.builder()
                .templateCode("A-17")
                .channel(NotificationChannel.EMAIL)
                .locale("en")
                .subject("subj")
                .bodyText("Hi {{applicantName}}. {{footerBlock}}")
                .variablesJson("[\"applicantName\"]")
                .enabled(enabled)
                .catalogMetaKey("A-17")
                .category(cat)
                .severity(NotificationSeverity.CRITICAL)
                .recipientRoles("APPLICANT")
                .build();
        // seq 는 final/private 이라 reflection 또는 spy 가 필요하지만 본 테스트는 행위만 검증
        return t;
    }

    // ============================================================
    // createDraft
    // ============================================================

    @Test
    @DisplayName("createDraft - lint 통과 시 PENDING 상태로 저장")
    void createDraft_success() {
        when(catalogRepository.findByTemplateCode("A-17")).thenReturn(Optional.empty());

        NotificationTemplateDraft draft = service.createDraft(cleanInput(), 1001L);

        assertThat(draft.getTemplateCode()).isEqualTo("A-17");
        assertThat(draft.getSubmittedBy()).isEqualTo(1001L);
        verify(draftRepository, times(1)).save(any(NotificationTemplateDraft.class));
    }

    @Test
    @DisplayName("createDraft - lint 실패 시 TemplateLintException 발생, 저장 안 함")
    void createDraft_lintFailure_throws() {
        NotificationTemplateAdminService.DraftMutationInput badInput =
                new NotificationTemplateAdminService.DraftMutationInput(
                        42L, "A-17", NotificationChannel.EMAIL, "en",
                        "subj",
                        "Body without footer token and {{unknownVar}}",
                        "[]", null, NotificationCategory.STATUS,
                        NotificationSeverity.IMPORTANT, "APPLICANT", null
                );
        when(catalogRepository.findByTemplateCode("A-17")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createDraft(badInput, 1001L))
                .isInstanceOf(TemplateLintException.class);
        verify(draftRepository, never()).save(any());
    }

    // ============================================================
    // editDraft ownership
    // ============================================================

    @Test
    @DisplayName("editDraft - 작성자 본인이 아니면 DraftOwnershipException")
    void editDraft_notOwnerThrows() {
        NotificationTemplateDraft draft = NotificationTemplateDraft.builder()
                .templateCode("A-17").channel(NotificationChannel.EMAIL).locale("en")
                .bodyText("Hi. {{footerBlock}}").submittedBy(1001L).build();
        when(draftRepository.findById(7L)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.editDraft(7L, cleanInput(), 9999L))
                .isInstanceOf(NotificationTemplateAdminService.DraftOwnershipException.class);
    }

    // ============================================================
    // enable / disable — D-6 + H-S3
    // ============================================================

    @Test
    @DisplayName("enableTemplate - 이미 enabled 면 no-op (history 적재 안 함)")
    void enableTemplate_idempotent() {
        NotificationTemplate t = buildTemplate(1L, NotificationCategory.STATUS, true);
        when(templateRepository.findById(1L)).thenReturn(Optional.of(t));

        service.enableTemplate(1L, null, 9001L, "127.0.0.1");

        verify(historyRepository, never()).save(any());
    }

    @Test
    @DisplayName("disableTemplate - STATUS 카테고리는 reason 없이도 OK, history 적재")
    void disableTemplate_statusCategoryNoReasonRequired() {
        NotificationTemplate t = buildTemplate(1L, NotificationCategory.STATUS, true);
        when(templateRepository.findById(1L)).thenReturn(Optional.of(t));

        service.disableTemplate(1L, null, 9001L, "127.0.0.1", false);

        assertThat(t.isEnabled()).isFalse();
        verify(historyRepository, times(1)).save(any(NotificationTemplateHistory.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("disableTemplate - PAYMENT 카테고리는 reason 필수 (D-6)")
    void disableTemplate_paymentRequiresReason() {
        NotificationTemplate t = buildTemplate(1L, NotificationCategory.PAYMENT, true);
        when(templateRepository.findById(1L)).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.disableTemplate(1L, "", 9001L, "127.0.0.1", true))
                .isInstanceOf(NotificationTemplateAdminService.ChangeReasonRequiredException.class);
    }

    @Test
    @DisplayName("disableTemplate - SECURITY 카테고리 + NM(non-SA) → 거부 (H-S3)")
    void disableTemplate_securityRequiresSystemAdmin() {
        NotificationTemplate t = buildTemplate(1L, NotificationCategory.SECURITY, true);
        when(templateRepository.findById(1L)).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.disableTemplate(
                1L,
                "긴급 점검 — 50자 이상의 상세 사유를 작성합니다. 실제로는 더 상세한 내용이 들어갑니다.",
                1001L, "127.0.0.1", /*isSystemAdmin*/ false))
                .isInstanceOf(NotificationTemplateAdminService.SecurityCategoryDisableNotPermittedException.class);
    }

    @Test
    @DisplayName("disableTemplate - SECURITY 카테고리 + SA + reason 50+자 → 이벤트 발행")
    void disableTemplate_securityWithLongReasonPublishesEvent() {
        NotificationTemplate t = buildTemplate(1L, NotificationCategory.SECURITY, true);
        when(templateRepository.findById(1L)).thenReturn(Optional.of(t));

        String reason = "보안팀 점검 — A-04 비번 변경 통보 임시 차단 (티켓 SEC-1234, ETA 30분, 사후 보고 예정)";
        service.disableTemplate(1L, reason, 9001L, "203.0.113.42", /*isSystemAdmin*/ true);

        assertThat(t.isEnabled()).isFalse();
        verify(historyRepository, times(1)).save(any(NotificationTemplateHistory.class));
        verify(eventPublisher, times(1)).publishEvent(any(SecurityTemplateDisableEvent.class));
    }

    @Test
    @DisplayName("disableTemplate - SECURITY + SA지만 reason 50자 미만이면 거부")
    void disableTemplate_securityShortReasonRejected() {
        NotificationTemplate t = buildTemplate(1L, NotificationCategory.SECURITY, true);
        when(templateRepository.findById(1L)).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.disableTemplate(
                1L, "긴급 점검", 9001L, "127.0.0.1", true))
                .isInstanceOf(NotificationTemplateAdminService.ChangeReasonRequiredException.class)
                .hasMessageContaining("50");
    }
}

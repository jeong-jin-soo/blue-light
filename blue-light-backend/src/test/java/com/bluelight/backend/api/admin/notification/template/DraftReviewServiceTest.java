package com.bluelight.backend.api.admin.notification.template;

import com.bluelight.backend.domain.notification.NotificationCategory;
import com.bluelight.backend.domain.notification.NotificationChannel;
import com.bluelight.backend.domain.notification.NotificationSeverity;
import com.bluelight.backend.domain.notification.NotificationTemplate;
import com.bluelight.backend.domain.notification.NotificationTemplateDraft;
import com.bluelight.backend.domain.notification.NotificationTemplateDraftRepository;
import com.bluelight.backend.domain.notification.NotificationTemplateHistory;
import com.bluelight.backend.domain.notification.NotificationTemplateHistoryRepository;
import com.bluelight.backend.domain.notification.NotificationTemplateRepository;
import com.bluelight.backend.domain.notification.TemplateDraftStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
 * DraftReviewService — D-1 2-step publish 워크플로 (approve/reject) 단위 테스트 (PR-T2).
 */
@DisplayName("DraftReviewService - PR-T2")
class DraftReviewServiceTest {

    private NotificationTemplateRepository templateRepository;
    private NotificationTemplateDraftRepository draftRepository;
    private NotificationTemplateHistoryRepository historyRepository;
    private DraftReviewService service;

    @BeforeEach
    void setUp() {
        templateRepository = mock(NotificationTemplateRepository.class);
        draftRepository = mock(NotificationTemplateDraftRepository.class);
        historyRepository = mock(NotificationTemplateHistoryRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        TemplateSnapshotMapper snapshotMapper = new TemplateSnapshotMapper(objectMapper);

        service = new DraftReviewService(templateRepository, draftRepository, historyRepository, snapshotMapper);

        when(templateRepository.save(any(NotificationTemplate.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private NotificationTemplateDraft buildDraft(Long templateSeq, NotificationCategory category) {
        return NotificationTemplateDraft.builder()
                .templateSeq(templateSeq)
                .templateCode("A-17")
                .channel(NotificationChannel.EMAIL)
                .locale("en")
                .subject("[LicenseKaki] Payment requested · #{{publicCode}}")
                .bodyText("Hi {{applicantName}}, please pay SGD {{amount}}. {{footerBlock}}")
                .variablesJson("[\"applicantName\",\"amount\",\"publicCode\"]")
                .providerTemplateName(null)
                .category(category)
                .severity(NotificationSeverity.CRITICAL)
                .recipientRoles("APPLICANT")
                .submittedBy(1001L)
                .submissionNote("법무 반영")
                .build();
    }

    private NotificationTemplate buildExistingTemplate() {
        return NotificationTemplate.builder()
                .templateCode("A-17")
                .channel(NotificationChannel.EMAIL)
                .locale("en")
                .subject("old subject")
                .bodyText("Old body. {{footerBlock}}")
                .variablesJson("[]")
                .enabled(true)
                .catalogMetaKey("A-17")
                .category(NotificationCategory.PAYMENT)
                .severity(NotificationSeverity.CRITICAL)
                .recipientRoles("APPLICANT")
                .build();
    }

    // ============================================================
    // approve - 신규 템플릿
    // ============================================================

    @Test
    @DisplayName("approve - templateSeq=null draft → 신규 NotificationTemplate 생성 + CREATE history")
    void approve_newTemplate() {
        NotificationTemplateDraft draft = buildDraft(null, NotificationCategory.STATUS);
        when(draftRepository.findById(1L)).thenReturn(Optional.of(draft));

        NotificationTemplate result = service.approve(1L, 9001L, "ok", "127.0.0.1");

        assertThat(result.getTemplateCode()).isEqualTo("A-17");
        assertThat(result.isEnabled()).isTrue();
        assertThat(draft.getStatus()).isEqualTo(TemplateDraftStatus.APPROVED);
        verify(templateRepository, times(1)).save(any(NotificationTemplate.class));
        verify(historyRepository, times(1)).save(any(NotificationTemplateHistory.class));
    }

    // ============================================================
    // approve - 기존 갱신
    // ============================================================

    @Test
    @DisplayName("approve - 기존 template 갱신 → applyPublishedSnapshot + PUBLISH history")
    void approve_existingTemplateUpdate() {
        NotificationTemplate existing = buildExistingTemplate();
        NotificationTemplateDraft draft = buildDraft(42L, NotificationCategory.PAYMENT);
        when(draftRepository.findById(1L)).thenReturn(Optional.of(draft));
        when(templateRepository.findById(42L)).thenReturn(Optional.of(existing));

        NotificationTemplate result = service.approve(1L, 9001L, "법무 요청 반영", "127.0.0.1");

        assertThat(result).isSameAs(existing); // 같은 row 갱신
        assertThat(existing.getSubject()).contains("Payment requested");
        assertThat(existing.getBodyText()).contains("{{applicantName}}");
        verify(templateRepository, never()).save(any()); // dirty checking, save 호출 안 함
        verify(historyRepository, times(1)).save(any(NotificationTemplateHistory.class));
        assertThat(draft.getStatus()).isEqualTo(TemplateDraftStatus.APPROVED);
    }

    // ============================================================
    // approve - D-6 사유 강제
    // ============================================================

    @Test
    @DisplayName("approve - PAYMENT 카테고리 publish 시 reviewNote 누락 → 거부 (D-6)")
    void approve_paymentRequiresReviewNote() {
        NotificationTemplateDraft draft = buildDraft(42L, NotificationCategory.PAYMENT);
        when(draftRepository.findById(1L)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.approve(1L, 9001L, null, "127.0.0.1"))
                .isInstanceOf(NotificationTemplateAdminService.ChangeReasonRequiredException.class);

        verify(historyRepository, never()).save(any());
    }

    @Test
    @DisplayName("approve - SECURITY 카테고리 publish 시 reviewNote 누락 → 거부")
    void approve_securityRequiresReviewNote() {
        NotificationTemplateDraft draft = buildDraft(42L, NotificationCategory.SECURITY);
        when(draftRepository.findById(1L)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.approve(1L, 9001L, "  ", "127.0.0.1"))
                .isInstanceOf(NotificationTemplateAdminService.ChangeReasonRequiredException.class);
    }

    @Test
    @DisplayName("approve - STATUS 카테고리는 reviewNote 옵션 — 누락해도 통과")
    void approve_statusReviewNoteOptional() {
        NotificationTemplateDraft draft = buildDraft(null, NotificationCategory.STATUS);
        when(draftRepository.findById(1L)).thenReturn(Optional.of(draft));

        service.approve(1L, 9001L, null, "127.0.0.1");

        assertThat(draft.getStatus()).isEqualTo(TemplateDraftStatus.APPROVED);
    }

    // ============================================================
    // reject
    // ============================================================

    @Test
    @DisplayName("reject - 정상 흐름 — REJECTED 상태 + reviewNote 기록")
    void reject_recordsNote() {
        NotificationTemplateDraft draft = buildDraft(42L, NotificationCategory.PAYMENT);
        when(draftRepository.findById(1L)).thenReturn(Optional.of(draft));

        service.reject(1L, 9001L, "PayNow UEN 리터럴 차단 — {{paynowUen}}로 교체 필요");

        assertThat(draft.getStatus()).isEqualTo(TemplateDraftStatus.REJECTED);
        assertThat(draft.getReviewedBy()).isEqualTo(9001L);
        assertThat(draft.getReviewNote()).contains("PayNow");
    }

    @Test
    @DisplayName("reject - reviewNote 누락 시 IllegalArgumentException (엔티티 가드)")
    void reject_blankNoteThrows() {
        NotificationTemplateDraft draft = buildDraft(42L, NotificationCategory.PAYMENT);
        when(draftRepository.findById(1L)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.reject(1L, 9001L, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

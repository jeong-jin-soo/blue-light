package com.bluelight.backend.api.admin.notification.template;

import com.bluelight.backend.api.admin.notification.template.dto.TemplatePreviewResponse;
import com.bluelight.backend.api.notification.template.TemplateRenderer;
import com.bluelight.backend.api.notification.template.lint.TemplateLinter;
import com.bluelight.backend.api.notification.template.lint.TemplateVariableValidator;
import com.bluelight.backend.domain.notification.NotificationCatalog;
import com.bluelight.backend.domain.notification.NotificationCatalogRepository;
import com.bluelight.backend.domain.notification.NotificationCategory;
import com.bluelight.backend.domain.notification.NotificationChannel;
import com.bluelight.backend.domain.notification.NotificationSeverity;
import com.bluelight.backend.domain.notification.NotificationTemplate;
import com.bluelight.backend.domain.notification.NotificationTemplateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TemplatePreviewService — 렌더링·missing key·SMS segment 계산 검증 (PR-T4).
 */
@DisplayName("TemplatePreviewService - PR-T4")
class TemplatePreviewServiceTest {

    private NotificationTemplateRepository templateRepository;
    private NotificationCatalogRepository catalogRepository;
    private TemplatePreviewService service;

    @BeforeEach
    void setUp() {
        templateRepository = mock(NotificationTemplateRepository.class);
        catalogRepository = mock(NotificationCatalogRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        TemplateVariableValidator validator = new TemplateVariableValidator(objectMapper);
        TemplateLinter linter = new TemplateLinter(validator);
        service = new TemplatePreviewService(
                templateRepository, catalogRepository,
                new TemplateRenderer(), linter, validator
        );
    }

    private NotificationTemplate buildEmailTemplate() {
        return NotificationTemplate.builder()
                .templateCode("A-17")
                .channel(NotificationChannel.EMAIL)
                .locale("en")
                .subject("[LicenseKaki] Payment requested · {{publicCode}}")
                .bodyText("Hi {{applicantName}}, please pay SGD {{amount}}. {{footerBlock}}")
                .variablesJson("[\"applicantName\",\"amount\",\"publicCode\"]")
                .enabled(true)
                .catalogMetaKey("A-17")
                .category(NotificationCategory.PAYMENT)
                .severity(NotificationSeverity.CRITICAL)
                .recipientRoles("APPLICANT")
                .build();
    }

    private NotificationTemplate buildSmsTemplate(String body) {
        return NotificationTemplate.builder()
                .templateCode("A-19")
                .channel(NotificationChannel.SMS)
                .locale("en")
                .subject(null)
                .bodyText(body)
                .variablesJson("[]")
                .enabled(true)
                .category(NotificationCategory.PAYMENT)
                .severity(NotificationSeverity.CRITICAL)
                .recipientRoles("APPLICANT")
                .build();
    }

    @Test
    @DisplayName("preview - 완전한 payload → 렌더된 subject/body + missing 없음")
    void preview_completePayload() {
        when(templateRepository.findById(42L)).thenReturn(Optional.of(buildEmailTemplate()));
        when(catalogRepository.findByTemplateCode("A-17")).thenReturn(Optional.empty());

        TemplatePreviewResponse resp = service.preview(42L, Map.of(
                "applicantName", "Tan Ah Kow",
                "amount", "185.00",
                "publicCode", "A-2026-0421"
        ));

        assertThat(resp.subject()).isEqualTo("[LicenseKaki] Payment requested · A-2026-0421");
        assertThat(resp.body()).contains("Tan Ah Kow", "SGD 185.00");
        assertThat(resp.missingKeys()).isEmpty();
        assertThat(resp.smsSegments()).isNull(); // EMAIL 채널
    }

    @Test
    @DisplayName("preview - payload 누락 시 missingKeys 채워짐 (UI 경고)")
    void preview_missingKeysReported() {
        when(templateRepository.findById(42L)).thenReturn(Optional.of(buildEmailTemplate()));
        when(catalogRepository.findByTemplateCode("A-17")).thenReturn(Optional.empty());

        TemplatePreviewResponse resp = service.preview(42L, Map.of(
                "applicantName", "Tan Ah Kow"
                // amount, publicCode 누락
        ));

        assertThat(resp.missingKeys()).contains("amount", "publicCode");
    }

    @Test
    @DisplayName("preview - SMS 채널 segment 계산 (160자 단위)")
    void preview_smsSegments() {
        String body200 = "a".repeat(200);
        when(templateRepository.findById(42L)).thenReturn(Optional.of(buildSmsTemplate(body200)));
        when(catalogRepository.findByTemplateCode("A-19")).thenReturn(Optional.empty());

        TemplatePreviewResponse resp = service.preview(42L, Map.of());

        assertThat(resp.charCount()).isEqualTo(200);
        assertThat(resp.smsSegments()).isEqualTo(2); // ceil(200/160)
    }

    @Test
    @DisplayName("preview - 미존재 템플릿 → TemplateNotFoundException")
    void preview_templateNotFound() {
        when(templateRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.preview(999L, Map.of()))
                .isInstanceOf(NotificationTemplateAdminService.TemplateNotFoundException.class);
    }

    @Test
    @DisplayName("preview - 카탈로그 메타가 있으면 lint warnings 도 함께 평가")
    void preview_withCatalogProducesWarnings() {
        NotificationTemplate templateWithPii = NotificationTemplate.builder()
                .templateCode("A-08")
                .channel(NotificationChannel.EMAIL)
                .locale("en")
                .subject("Hi {{applicantName}}") // L6 PII warning
                .bodyText("Hi {{applicantName}}. {{footerBlock}}")
                .variablesJson("[\"applicantName\"]")
                .enabled(true)
                .category(NotificationCategory.STATUS)
                .severity(NotificationSeverity.IMPORTANT)
                .recipientRoles("APPLICANT")
                .build();
        when(templateRepository.findById(8L)).thenReturn(Optional.of(templateWithPii));
        when(catalogRepository.findByTemplateCode("A-08")).thenReturn(Optional.of(
                NotificationCatalog.builder()
                        .templateCode("A-08")
                        .allowedVariablesJson("[\"applicantName\"]")
                        .defaultCategory(NotificationCategory.STATUS)
                        .defaultSeverity(NotificationSeverity.IMPORTANT)
                        .defaultRecipientRoles("APPLICANT")
                        .description("Application submitted")
                        .build()));

        TemplatePreviewResponse resp = service.preview(8L, Map.of("applicantName", "Tan"));

        assertThat(resp.warnings()).anyMatch(i -> i.ruleCode().equals("L6_PII_SUBJECT"));
    }
}

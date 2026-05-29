package com.bluelight.backend.api.admin.notification.template;

import com.bluelight.backend.api.admin.notification.template.dto.ImportReportResponse;
import com.bluelight.backend.api.admin.notification.template.dto.LocalizationFormat;
import com.bluelight.backend.domain.notification.NotificationCategory;
import com.bluelight.backend.domain.notification.NotificationChannel;
import com.bluelight.backend.domain.notification.NotificationSeverity;
import com.bluelight.backend.domain.notification.NotificationTemplate;
import com.bluelight.backend.domain.notification.NotificationTemplateDraft;
import com.bluelight.backend.domain.notification.NotificationTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TemplateLocalizationService — XLIFF/CSV export·import 검증 (PR-T7 P1).
 */
@DisplayName("TemplateLocalizationService - PR-T7 P1")
class TemplateLocalizationServiceTest {

    private NotificationTemplateRepository templateRepository;
    private NotificationTemplateAdminService adminService;
    private TemplateLocalizationService service;

    @BeforeEach
    void setUp() {
        templateRepository = mock(NotificationTemplateRepository.class);
        adminService = mock(NotificationTemplateAdminService.class);
        service = new TemplateLocalizationService(templateRepository, adminService);
    }

    private NotificationTemplate enTemplate(String code, NotificationChannel channel,
                                            String subject, String body) {
        NotificationTemplate t = NotificationTemplate.builder()
                .templateCode(code)
                .channel(channel)
                .locale("en")
                .subject(subject)
                .bodyText(body)
                .enabled(true)
                .category(NotificationCategory.STATUS)
                .severity(NotificationSeverity.INFORMATIONAL)
                .recipientRoles("APPLICANT")
                .build();
        ReflectionTestUtils.setField(t, "templateSeq", 100L + code.hashCode() % 1000);
        return t;
    }

    // ============================================================
    // Export — XLIFF
    // ============================================================

    @Test
    @DisplayName("XLIFF export — trans-unit id 가 {code}|{channel}|{field} 형식")
    void exportXliff_buildsTransUnits() {
        when(templateRepository.findByLocaleAndEnabledOrderByTemplateCodeAscChannelAsc("en", true))
                .thenReturn(List.of(
                        enTemplate("A-17", NotificationChannel.EMAIL,
                                "Payment requested · #{{publicCode}}",
                                "Hi {{applicantName}}, please pay.")
                ));

        byte[] out = service.export("en", LocalizationFormat.XLIFF);
        String xml = new String(out, StandardCharsets.UTF_8);

        assertThat(xml).contains("<xliff");
        assertThat(xml).contains("source-language=\"en\"");
        assertThat(xml).contains("target-language=\"__TBD__\"");
        assertThat(xml).contains("id=\"A-17|EMAIL|subject\"");
        assertThat(xml).contains("id=\"A-17|EMAIL|body\"");
        assertThat(xml).contains("Payment requested · #{{publicCode}}");
        assertThat(xml).contains("Hi {{applicantName}}, please pay.");
    }

    @Test
    @DisplayName("XLIFF export — subject 없는 채널(SMS)은 subject trans-unit 생략")
    void exportXliff_skipsEmptySubject() {
        when(templateRepository.findByLocaleAndEnabledOrderByTemplateCodeAscChannelAsc("en", true))
                .thenReturn(List.of(
                        enTemplate("R-09", NotificationChannel.SMS, null, "[LicenseKaki] OTP 123456")
                ));

        String xml = new String(service.export("en", LocalizationFormat.XLIFF), StandardCharsets.UTF_8);
        assertThat(xml).doesNotContain("R-09|SMS|subject");
        assertThat(xml).contains("R-09|SMS|body");
    }

    // ============================================================
    // Export — CSV
    // ============================================================

    @Test
    @DisplayName("CSV export — RFC 4180 quote 처리 + UTF-8 BOM")
    void exportCsv_quotesEscaped() {
        when(templateRepository.findByLocaleAndEnabledOrderByTemplateCodeAscChannelAsc("en", true))
                .thenReturn(List.of(
                        enTemplate("A-17", NotificationChannel.EMAIL,
                                "Payment, please",
                                "Body with \"quotes\" and\nnewline")
                ));

        byte[] out = service.export("en", LocalizationFormat.CSV);
        String csv = new String(out, StandardCharsets.UTF_8);

        // BOM
        assertThat(csv.charAt(0)).isEqualTo('﻿');
        // 헤더
        assertThat(csv).contains("template_code,channel,locale,subject,body_text");
        // 쉼표 포함 필드 quote
        assertThat(csv).contains("\"Payment, please\"");
        // 내부 quote escape (2배)
        assertThat(csv).contains("\"\"quotes\"\"");
        // newline 포함 필드 quote
        assertThat(csv).contains("\"Body with \"\"quotes\"\" and\nnewline\"");
    }

    // ============================================================
    // Import — XLIFF
    // ============================================================

    @Test
    @DisplayName("XLIFF import — 같은 (code, channel)의 subject + body가 하나의 draft로 그룹화")
    void importXliff_groupsSubjectAndBody() {
        NotificationTemplate base = enTemplate("A-17", NotificationChannel.EMAIL,
                "Payment requested · #{{publicCode}}",
                "Hi {{applicantName}}");
        when(templateRepository.findByTemplateCodeAndChannelAndLocale(
                eq("A-17"), eq(NotificationChannel.EMAIL), eq("en")))
                .thenReturn(Optional.of(base));
        when(templateRepository.findByTemplateCodeAndChannelAndLocale(
                eq("A-17"), eq(NotificationChannel.EMAIL), eq("ko")))
                .thenReturn(Optional.empty());

        NotificationTemplateDraft draft = mock(NotificationTemplateDraft.class);
        when(draft.getDraftSeq()).thenReturn(900L);
        when(adminService.createDraft(any(), eq(7001L))).thenReturn(draft);

        String xliff = """
                <?xml version="1.0" encoding="UTF-8"?>
                <xliff version="1.2" xmlns="urn:oasis:names:tc:xliff:document:1.2">
                  <file source-language="en" target-language="ko" datatype="plaintext" original="t">
                    <body>
                      <trans-unit id="A-17|EMAIL|subject">
                        <source>Payment requested · #{{publicCode}}</source>
                        <target>결제 요청 · #{{publicCode}}</target>
                      </trans-unit>
                      <trans-unit id="A-17|EMAIL|body">
                        <source>Hi {{applicantName}}</source>
                        <target>안녕하세요 {{applicantName}}</target>
                      </trans-unit>
                    </body>
                  </file>
                </xliff>
                """;

        ImportReportResponse report = service.importTemplates(
                "ko", LocalizationFormat.XLIFF,
                new ByteArrayInputStream(xliff.getBytes(StandardCharsets.UTF_8)),
                7001L);

        assertThat(report.totalRows()).isEqualTo(1);
        assertThat(report.draftsCreated()).isEqualTo(1);
        assertThat(report.skipped()).isZero();
        assertThat(report.failed()).isZero();
        verify(adminService).createDraft(any(), eq(7001L));
    }

    @Test
    @DisplayName("XLIFF import — target에 base에 없는 변수가 있으면 FAILED (L1)")
    void importXliff_unknownVariableFails() {
        NotificationTemplate base = enTemplate("A-17", NotificationChannel.EMAIL,
                "Payment", "Hi {{applicantName}}");
        when(templateRepository.findByTemplateCodeAndChannelAndLocale(
                eq("A-17"), eq(NotificationChannel.EMAIL), eq("en")))
                .thenReturn(Optional.of(base));

        String xliff = """
                <?xml version="1.0" encoding="UTF-8"?>
                <xliff version="1.2" xmlns="urn:oasis:names:tc:xliff:document:1.2">
                  <file source-language="en" target-language="ko" datatype="plaintext" original="t">
                    <body>
                      <trans-unit id="A-17|EMAIL|body">
                        <source>Hi {{applicantName}}</source>
                        <target>안녕 {{unknownVar}}</target>
                      </trans-unit>
                    </body>
                  </file>
                </xliff>
                """;

        ImportReportResponse report = service.importTemplates(
                "ko", LocalizationFormat.XLIFF,
                new ByteArrayInputStream(xliff.getBytes(StandardCharsets.UTF_8)),
                7001L);

        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.items().get(0).reason()).contains("unknownVar");
        verify(adminService, never()).createDraft(any(), anyLong());
    }

    @Test
    @DisplayName("XLIFF import — base 템플릿 없으면 SKIPPED")
    void importXliff_noBaseSkipped() {
        when(templateRepository.findByTemplateCodeAndChannelAndLocale(
                any(), any(), eq("en")))
                .thenReturn(Optional.empty());

        String xliff = """
                <?xml version="1.0" encoding="UTF-8"?>
                <xliff version="1.2" xmlns="urn:oasis:names:tc:xliff:document:1.2">
                  <file source-language="en" target-language="ko" datatype="plaintext" original="t">
                    <body>
                      <trans-unit id="X-99|EMAIL|body">
                        <source>foo</source>
                        <target>bar</target>
                      </trans-unit>
                    </body>
                  </file>
                </xliff>
                """;

        ImportReportResponse report = service.importTemplates(
                "ko", LocalizationFormat.XLIFF,
                new ByteArrayInputStream(xliff.getBytes(StandardCharsets.UTF_8)),
                7001L);

        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.items().get(0).reason()).contains("No base");
        verify(adminService, never()).createDraft(any(), anyLong());
    }

    @Test
    @DisplayName("XLIFF import — 빈 target은 SKIPPED (translation pending)")
    void importXliff_emptyTargetSkipped() {
        NotificationTemplate base = enTemplate("A-17", NotificationChannel.EMAIL,
                "Subject", "Body");
        when(templateRepository.findByTemplateCodeAndChannelAndLocale(
                eq("A-17"), eq(NotificationChannel.EMAIL), eq("en")))
                .thenReturn(Optional.of(base));

        String xliff = """
                <?xml version="1.0" encoding="UTF-8"?>
                <xliff version="1.2" xmlns="urn:oasis:names:tc:xliff:document:1.2">
                  <file source-language="en" target-language="ko" datatype="plaintext" original="t">
                    <body>
                      <trans-unit id="A-17|EMAIL|body">
                        <source>Body</source>
                        <target></target>
                      </trans-unit>
                    </body>
                  </file>
                </xliff>
                """;

        ImportReportResponse report = service.importTemplates(
                "ko", LocalizationFormat.XLIFF,
                new ByteArrayInputStream(xliff.getBytes(StandardCharsets.UTF_8)),
                7001L);

        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.items().get(0).reason()).contains("Empty");
    }

    // ============================================================
    // Import — CSV
    // ============================================================

    @Test
    @DisplayName("CSV import — RFC 4180 quote 파싱 + draft 생성")
    void importCsv_basicRoundtrip() {
        NotificationTemplate base = enTemplate("A-17", NotificationChannel.EMAIL,
                "Payment", "Hi {{applicantName}}");
        when(templateRepository.findByTemplateCodeAndChannelAndLocale(
                eq("A-17"), eq(NotificationChannel.EMAIL), eq("en")))
                .thenReturn(Optional.of(base));
        when(templateRepository.findByTemplateCodeAndChannelAndLocale(
                eq("A-17"), eq(NotificationChannel.EMAIL), eq("ko")))
                .thenReturn(Optional.empty());
        NotificationTemplateDraft draft = mock(NotificationTemplateDraft.class);
        when(draft.getDraftSeq()).thenReturn(800L);
        when(adminService.createDraft(any(), eq(7001L))).thenReturn(draft);

        String csv = """
                template_code,channel,locale,subject,body_text
                A-17,EMAIL,ko,"결제, 안내","안녕하세요 {{applicantName}}"
                """;

        ImportReportResponse report = service.importTemplates(
                "ko", LocalizationFormat.CSV,
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
                7001L);

        assertThat(report.draftsCreated()).isEqualTo(1);
        verify(adminService).createDraft(any(), eq(7001L));
    }

    @Test
    @DisplayName("CSV parseCsvContent — RFC 4180 escape + 줄바꿈 포함 필드")
    void csvParser_handlesQuotedNewlines() {
        String csv = "a,b,c\nfoo,\"two\nlines\",bar\n";
        List<String[]> rows = TemplateLocalizationService.parseCsvContent(csv);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsExactly("a", "b", "c");
        assertThat(rows.get(1)).containsExactly("foo", "two\nlines", "bar");
    }

    // ============================================================
    // Edge cases
    // ============================================================

    @Test
    @DisplayName("import: base locale (en) 으로 import 시도 → LocalizationException")
    void importToBaseLocaleRejected() {
        assertThatThrownBy(() -> service.importTemplates(
                "en", LocalizationFormat.XLIFF,
                new ByteArrayInputStream("<xliff/>".getBytes(StandardCharsets.UTF_8)),
                7001L))
                .isInstanceOf(TemplateLocalizationService.LocalizationException.class)
                .hasMessageContaining("base locale");
    }

    @Test
    @DisplayName("import: targetLocale 누락 → LocalizationException")
    void importBlankLocaleRejected() {
        assertThatThrownBy(() -> service.importTemplates(
                "", LocalizationFormat.XLIFF,
                new ByteArrayInputStream("<xliff/>".getBytes(StandardCharsets.UTF_8)),
                7001L))
                .isInstanceOf(TemplateLocalizationService.LocalizationException.class);
    }

    @Test
    @DisplayName("XLIFF parsing: malformed id (separator != 2) → 그 unit만 무시")
    void importXliff_malformedIdSkipped() {
        String xliff = """
                <?xml version="1.0" encoding="UTF-8"?>
                <xliff version="1.2" xmlns="urn:oasis:names:tc:xliff:document:1.2">
                  <file source-language="en" target-language="ko" datatype="plaintext" original="t">
                    <body>
                      <trans-unit id="malformed-id">
                        <source>foo</source>
                        <target>bar</target>
                      </trans-unit>
                    </body>
                  </file>
                </xliff>
                """;

        ImportReportResponse report = service.importTemplates(
                "ko", LocalizationFormat.XLIFF,
                new ByteArrayInputStream(xliff.getBytes(StandardCharsets.UTF_8)),
                7001L);

        assertThat(report.totalRows()).isZero();
    }
}

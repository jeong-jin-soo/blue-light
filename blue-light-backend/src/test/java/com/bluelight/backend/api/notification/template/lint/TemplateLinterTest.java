package com.bluelight.backend.api.notification.template.lint;

import com.bluelight.backend.domain.notification.NotificationCategory;
import com.bluelight.backend.domain.notification.NotificationChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TemplateLinter L1~L8 단위 테스트 — 각 규칙의 positive(통과) + negative(차단) 검증.
 *
 * <p>각 테스트는 helper 로 "모든 규칙을 통과하는 깨끗한 input" 을 만든 뒤 한 필드만 변형하여
 * 해당 규칙만 fail 하도록 한다 — 8개 규칙의 독립성 검증.</p>
 */
@DisplayName("TemplateLinter L1~L8 - PR-T2")
class TemplateLinterTest {

    private final TemplateVariableValidator validator = new TemplateVariableValidator(new ObjectMapper());
    private final TemplateLinter linter = new TemplateLinter(validator);

    /** 8개 규칙 전부 통과하는 EMAIL 본문 — 각 테스트가 한 필드만 바꿔서 위배 케이스 생성. */
    private LintInput cleanEmail() {
        return new LintInput(
                "A-17",
                NotificationChannel.EMAIL,
                "[LicenseKaki] Payment requested",
                "Hi {{applicantName}}, please pay SGD {{amount}}. {{footerBlock}}",
                NotificationCategory.PAYMENT,
                null,
                "[\"applicantName\",\"amount\"]",
                "[\"applicantName\",\"amount\",\"publicCode\"]",
                null
        );
    }

    @Test
    @DisplayName("baseline - cleanEmail 은 모든 규칙 통과")
    void baseline_cleanEmail_passesAllRules() {
        LintResult result = linter.lint(cleanEmail());
        assertThat(result.isPassed()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    // ============================================================
    // L1 — Variable whitelist
    // ============================================================

    @Test
    @DisplayName("L1 - 미정의 변수 사용 시 ERROR")
    void l1_undefinedVariableTriggersError() {
        LintInput input = new LintInput(
                "A-17", NotificationChannel.EMAIL, "subject",
                "Hi {{unknownVar}}, {{footerBlock}}",
                NotificationCategory.STATUS, null,
                "[]", "[\"applicantName\"]", null
        );

        LintResult result = linter.lint(input);

        assertThat(result.errors()).anyMatch(i -> i.ruleCode().equals("L1_VARIABLE_WHITELIST"));
        assertThat(result.errors().get(0).message()).contains("unknownVar");
    }

    @Test
    @DisplayName("L1 - 시스템 토큰(footerBlock/optOutUrl/paynowUen)은 화이트리스트 면제")
    void l1_systemTokensAreExempt() {
        LintInput input = new LintInput(
                "A-17", NotificationChannel.EMAIL, "subject",
                "{{footerBlock}} {{optOutUrl}} {{paynowUen}} {{paynowReference}}",
                NotificationCategory.STATUS, null,
                "[]", "[]", null
        );

        LintResult result = linter.lint(input);

        assertThat(result.errors()).noneMatch(i -> i.ruleCode().equals("L1_VARIABLE_WHITELIST"));
    }

    // ============================================================
    // L2 — SMS 160 chars (prefix 포함)
    // ============================================================

    @Test
    @DisplayName("L2 - SMS 본문 + prefix 합산 160자 초과 시 ERROR")
    void l2_smsOverLengthTriggersError() {
        String longBody = "a".repeat(200);
        LintInput input = new LintInput(
                "A-19", NotificationChannel.SMS, null,
                longBody,
                NotificationCategory.PAYMENT, null,
                "[]", "[]", null
        );

        LintResult result = linter.lint(input);

        assertThat(result.errors()).anyMatch(i -> i.ruleCode().equals("L2_SMS_LENGTH"));
    }

    @Test
    @DisplayName("L2 - SMS 160자 이하는 통과")
    void l2_smsUnderLengthPasses() {
        LintInput input = new LintInput(
                "A-19", NotificationChannel.SMS, null,
                "Payment due 25 Apr. lk.sg/p/X",
                NotificationCategory.PAYMENT, null,
                "[]", "[]", null
        );

        LintResult result = linter.lint(input);

        assertThat(result.errors()).noneMatch(i -> i.ruleCode().equals("L2_SMS_LENGTH"));
    }

    // ============================================================
    // L3 — MARKETING [ADV] prefix
    // ============================================================

    @Test
    @DisplayName("L3 - MARKETING + EMAIL + subject 가 [ADV] 로 시작하지 않으면 ERROR")
    void l3_marketingSubjectWithoutAdvPrefix() {
        LintInput input = new LintInput(
                "M-NEW", NotificationChannel.EMAIL, "Special offer just for you",
                "Hi! Check out {{optOutUrl}}. {{footerBlock}}",
                NotificationCategory.MARKETING, null,
                "[]", "[]", null
        );

        LintResult result = linter.lint(input);

        assertThat(result.errors()).anyMatch(i -> i.ruleCode().equals("L3_MARKETING_ADV_PREFIX"));
    }

    @Test
    @DisplayName("L3 - MARKETING 이 아니면 [ADV] 검증 안 함")
    void l3_nonMarketingSkipsAdvPrefix() {
        LintInput input = new LintInput(
                "A-17", NotificationChannel.EMAIL, "Payment requested",
                "Hi {{applicantName}}, please pay. {{footerBlock}}",
                NotificationCategory.PAYMENT, null,
                "[\"applicantName\"]", "[\"applicantName\"]", null
        );

        LintResult result = linter.lint(input);

        assertThat(result.errors()).noneMatch(i -> i.ruleCode().equals("L3_MARKETING_ADV_PREFIX"));
    }

    // ============================================================
    // L4 — MARKETING opt-out token
    // ============================================================

    @Test
    @DisplayName("L4 - MARKETING 본문에 {{optOutUrl}} 누락 시 ERROR")
    void l4_marketingWithoutOptOut() {
        LintInput input = new LintInput(
                "M-NEW", NotificationChannel.EMAIL, "[ADV] Offer",
                "Body without opt out. {{footerBlock}}",
                NotificationCategory.MARKETING, null,
                "[]", "[]", null
        );

        LintResult result = linter.lint(input);

        assertThat(result.errors()).anyMatch(i -> i.ruleCode().equals("L4_MARKETING_OPT_OUT"));
    }

    // ============================================================
    // L5 — PayNow literal block
    // ============================================================

    @Test
    @DisplayName("L5 - UEN 8~10자리+대문자 패턴 발견 시 ERROR")
    void l5_paynowUenLiteralTriggers() {
        LintInput input = new LintInput(
                "A-17", NotificationChannel.EMAIL, "Payment",
                "Pay to UEN 201912345A by tomorrow. {{footerBlock}}",
                NotificationCategory.PAYMENT, null,
                "[]", "[]", null
        );

        LintResult result = linter.lint(input);

        assertThat(result.errors()).anyMatch(i -> i.ruleCode().equals("L5_PAYNOW_LITERAL"));
    }

    @Test
    @DisplayName("L5 - T-prefix UEN (T19LL1234A) 도 감지")
    void l5_paynowTPrefixUen() {
        LintInput input = new LintInput(
                "A-17", NotificationChannel.EMAIL, "Payment",
                "Send to T19LL1234A. {{footerBlock}}",
                NotificationCategory.PAYMENT, null,
                "[]", "[]", null
        );

        LintResult result = linter.lint(input);

        assertThat(result.errors()).anyMatch(i -> i.ruleCode().equals("L5_PAYNOW_LITERAL"));
    }

    @Test
    @DisplayName("L5 - 변수만 사용 시 통과 ({{paynowUen}})")
    void l5_paynowVariableIsClean() {
        LintInput input = new LintInput(
                "A-17", NotificationChannel.EMAIL, "Payment",
                "Pay to {{paynowUen}} ref {{paynowReference}}. {{footerBlock}}",
                NotificationCategory.PAYMENT, null,
                "[]", "[]", null
        );

        LintResult result = linter.lint(input);

        assertThat(result.errors()).noneMatch(i -> i.ruleCode().equals("L5_PAYNOW_LITERAL"));
    }

    // ============================================================
    // L6 — PII subject/SMS (WARNING)
    // ============================================================

    @Test
    @DisplayName("L6 - subject 에 PII 변수 ({{applicantName}}) 사용 시 WARNING")
    void l6_piiInSubjectIsWarning() {
        LintInput input = new LintInput(
                "A-08", NotificationChannel.EMAIL, "[LicenseKaki] Hi {{applicantName}}",
                "Body. {{footerBlock}}",
                NotificationCategory.STATUS, null,
                "[\"applicantName\"]", "[\"applicantName\"]", null
        );

        LintResult result = linter.lint(input);

        assertThat(result.warnings()).anyMatch(i -> i.ruleCode().equals("L6_PII_SUBJECT"));
        assertThat(result.isPassed()).isTrue(); // WARNING 은 차단 안 함
    }

    @Test
    @DisplayName("L6 - SMS body 에 PII 변수 사용 시 WARNING")
    void l6_piiInSmsIsWarning() {
        LintInput input = new LintInput(
                "A-19", NotificationChannel.SMS, null,
                "Hi {{applicantName}}, pay {{amount}}",
                NotificationCategory.PAYMENT, null,
                "[\"applicantName\",\"amount\"]", "[\"applicantName\",\"amount\"]", null
        );

        LintResult result = linter.lint(input);

        assertThat(result.warnings()).anyMatch(i -> i.ruleCode().equals("L6_PII_SMS"));
        assertThat(result.isPassed()).isTrue();
    }

    // ============================================================
    // L7 — EMAIL footer token
    // ============================================================

    @Test
    @DisplayName("L7 - EMAIL 본문에 {{footerBlock}} 누락 시 ERROR")
    void l7_emailWithoutFooterToken() {
        LintInput input = new LintInput(
                "A-08", NotificationChannel.EMAIL, "subject",
                "Body without footer token",
                NotificationCategory.STATUS, null,
                "[]", "[]", null
        );

        LintResult result = linter.lint(input);

        assertThat(result.errors()).anyMatch(i -> i.ruleCode().equals("L7_EMAIL_FOOTER"));
    }

    @Test
    @DisplayName("L7 - IN_APP 채널은 footer 검증 면제")
    void l7_inAppExemptFromFooter() {
        LintInput input = new LintInput(
                "A-08", NotificationChannel.IN_APP, null,
                "Body without footer",
                NotificationCategory.STATUS, null,
                "[]", "[]", null
        );

        LintResult result = linter.lint(input);

        assertThat(result.errors()).noneMatch(i -> i.ruleCode().equals("L7_EMAIL_FOOTER"));
    }

    // ============================================================
    // L8 — WHATSAPP positional vars + providerTemplateName
    // ============================================================

    @Test
    @DisplayName("L8 - WHATSAPP 본문에 이름 변수 ({{foo}}) 사용 시 ERROR")
    void l8_whatsappNamedVarsBlocked() {
        LintInput input = new LintInput(
                "A-17", NotificationChannel.WHATSAPP, null,
                "Hi {{applicantName}}, pay now.",
                NotificationCategory.PAYMENT,
                "licensekaki_payment_requested_en",
                "[\"applicantName\"]", "[\"applicantName\"]", null
        );

        LintResult result = linter.lint(input);

        assertThat(result.errors()).anyMatch(i -> i.ruleCode().equals("L8_WHATSAPP_POSITIONAL"));
    }

    @Test
    @DisplayName("L8 - WHATSAPP providerTemplateName 누락 시 ERROR")
    void l8_whatsappMissingProviderTemplateName() {
        LintInput input = new LintInput(
                "A-17", NotificationChannel.WHATSAPP, null,
                "Hi {{1}}, pay {{2}}.",
                NotificationCategory.PAYMENT,
                null,  // ← missing
                "[]", "[]", null
        );

        LintResult result = linter.lint(input);

        assertThat(result.errors()).anyMatch(i -> i.ruleCode().equals("L8_WHATSAPP_POSITIONAL")
                && "providerTemplateName".equals(i.field()));
    }

    @Test
    @DisplayName("L8 - WHATSAPP 위치 변수만 + providerTemplateName 있으면 통과")
    void l8_whatsappPositionalOnlyPasses() {
        LintInput input = new LintInput(
                "A-17", NotificationChannel.WHATSAPP, null,
                "Hi {{1}}, pay SGD {{2}} by {{3}}.",
                NotificationCategory.PAYMENT,
                "licensekaki_payment_requested_en",
                "[]", "[]", null
        );

        LintResult result = linter.lint(input);

        assertThat(result.errors()).noneMatch(i -> i.ruleCode().equals("L8_WHATSAPP_POSITIONAL"));
    }
}

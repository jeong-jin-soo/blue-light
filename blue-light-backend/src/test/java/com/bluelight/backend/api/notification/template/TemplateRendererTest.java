package com.bluelight.backend.api.notification.template;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TemplateRenderer 단위 테스트 (PR-0B). {@code {{var}}} 치환 + 누락 + 공백 + 특수문자.
 */
@DisplayName("TemplateRenderer - PR-0B")
class TemplateRendererTest {

    private final TemplateRenderer renderer = new TemplateRenderer();

    @Test
    @DisplayName("단순 치환 - {{key}} → payload 값")
    void renders_singleVariable() {
        String result = renderer.render("Hello, {{name}}!", Map.of("name", "Ringo"));
        assertThat(result).isEqualTo("Hello, Ringo!");
    }

    @Test
    @DisplayName("다중 치환 - 여러 변수가 한 본문에")
    void renders_multipleVariables() {
        String result = renderer.render(
                "Payment of S${{amount}} for APP-{{applicationCode}}.",
                Map.of("amount", "185.00", "applicationCode", "2026-00428"));
        assertThat(result).isEqualTo("Payment of S$185.00 for APP-2026-00428.");
    }

    @Test
    @DisplayName("공백 허용 - {{ key }} 도 동작")
    void renders_keyWithWhitespace() {
        String result = renderer.render("Hi, {{ name }}!", Map.of("name", "Ringo"));
        assertThat(result).isEqualTo("Hi, Ringo!");
    }

    @Test
    @DisplayName("누락 키 - 빈 문자열로 치환 (lenient)")
    void renders_missingKeyAsEmpty() {
        String result = renderer.render("Hello, {{name}}!", Map.of());
        assertThat(result).isEqualTo("Hello, !");
    }

    @Test
    @DisplayName("payload null 허용 - 빈 Map 으로 정규화")
    void renders_nullPayloadAsEmpty() {
        String result = renderer.render("Hello, {{name}}!", null);
        assertThat(result).isEqualTo("Hello, !");
    }

    @Test
    @DisplayName("템플릿 null/empty - 빈 문자열 반환")
    void renders_blankTemplate() {
        assertThat(renderer.render(null, Map.of("x", "y"))).isEqualTo("");
        assertThat(renderer.render("", Map.of("x", "y"))).isEqualTo("");
    }

    @Test
    @DisplayName("특수문자 안전 - $ 같은 정규식 replacement 메타문자 escape")
    void renders_handlesRegexMetaInPayload() {
        // $ 는 Matcher.appendReplacement 에서 group 참조로 해석되므로 quoteReplacement 가 필요.
        // 본 구현은 quoteReplacement 사용 — 안전성 검증.
        Map<String, String> payload = new HashMap<>();
        payload.put("amount", "$1.50 + $0.75");
        String result = renderer.render("Total: {{amount}}", payload);
        assertThat(result).isEqualTo("Total: $1.50 + $0.75");
    }

    @Test
    @DisplayName("점/대시/언더스코어 키 - 복합 변수명 지원")
    void renders_compoundKeys() {
        String result = renderer.render(
                "{{user.first_name}} ({{lew-grade}})",
                Map.of("user.first_name", "Alice", "lew-grade", "GRADE_9"));
        assertThat(result).isEqualTo("Alice (GRADE_9)");
    }

    @Test
    @DisplayName("안전장치 - optional `?` 토큰 {{managerNote?}} 은 고객 노출 없이 제거")
    void renders_stripsOptionalQuestionMarkToken() {
        // 카피북 optional 표기가 그대로 들어간 토큰은 VAR_PATTERN 미매칭 → 잔여 제거로 비워짐.
        String result = renderer.render(
                "Note: {{managerNote?}} end", Map.of("managerNote", "ignored"));
        assertThat(result).isEqualTo("Note:  end");
        assertThat(result).doesNotContain("{{");
    }

    @Test
    @DisplayName("안전장치 - 치환되지 않은 어떤 {{...}} 도 출력에 남지 않음")
    void renders_neverLeaksAnyPlaceholder() {
        String result = renderer.render(
                "Hi {{applicantName}}, ref {{unknownKey}} / {{weird key!}}",
                Map.of("applicantName", "Bob"));
        assertThat(result).isEqualTo("Hi Bob, ref  / ");
        assertThat(result).doesNotContain("{{");
        assertThat(result).doesNotContain("}}");
    }
}

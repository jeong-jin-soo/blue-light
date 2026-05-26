package com.bluelight.backend.api.notification.template.lint;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TemplateVariableValidator — 변수 추출·JSON 파싱 단위 테스트 (PR-T2).
 */
@DisplayName("TemplateVariableValidator - PR-T2")
class TemplateVariableValidatorTest {

    private final TemplateVariableValidator validator = new TemplateVariableValidator(new ObjectMapper());

    @Test
    @DisplayName("extractVariables - 본문에서 모든 {{key}} 추출 (등장순 보존, 중복 제거)")
    void extractVariables_findsAllUniqueKeys() {
        String body = "Hi {{applicantName}}, please pay SGD {{amount}} for {{applicantName}}'s app.";

        Set<String> keys = validator.extractVariables(body);

        assertThat(keys).containsExactly("applicantName", "amount");
    }

    @Test
    @DisplayName("extractVariables - 빈 입력 / null → 빈 셋")
    void extractVariables_handlesEmptyAndNull() {
        assertThat(validator.extractVariables(null)).isEmpty();
        assertThat(validator.extractVariables("")).isEmpty();
        assertThat(validator.extractVariables("No variables here.")).isEmpty();
    }

    @Test
    @DisplayName("extractVariables - 공백 허용 ({{ key }} → key)")
    void extractVariables_acceptsWhitespaceInBraces() {
        Set<String> keys = validator.extractVariables("Hi {{  applicantName  }}, code {{ publicCode }}.");

        assertThat(keys).containsExactly("applicantName", "publicCode");
    }

    @Test
    @DisplayName("extractVariables - 하이픈·점·언더바·숫자 수용")
    void extractVariables_acceptsDotsHyphensDigits() {
        Set<String> keys = validator.extractVariables("{{order.id}}, {{user-name}}, {{count_2}}, {{a1.b-c_d}}");

        assertThat(keys).containsExactlyInAnyOrder("order.id", "user-name", "count_2", "a1.b-c_d");
    }

    @Test
    @DisplayName("parseVariableSet - 유효한 JSON 배열 파싱")
    void parseVariableSet_validJsonArray() {
        Set<String> keys = validator.parseVariableSet("[\"applicantName\",\"amount\",\"publicCode\"]");

        assertThat(keys).containsExactly("applicantName", "amount", "publicCode");
    }

    @Test
    @DisplayName("parseVariableSet - null/빈 문자열 → 빈 셋 (저장 자체는 막지 않음)")
    void parseVariableSet_blankInputReturnsEmpty() {
        assertThat(validator.parseVariableSet(null)).isEmpty();
        assertThat(validator.parseVariableSet("")).isEmpty();
        assertThat(validator.parseVariableSet("   ")).isEmpty();
    }

    @Test
    @DisplayName("parseVariableSet - 파싱 실패 시 빈 셋 + WARN 로그 (lenient)")
    void parseVariableSet_malformedJsonReturnsEmpty() {
        assertThat(validator.parseVariableSet("not a json")).isEmpty();
        assertThat(validator.parseVariableSet("{\"this\":\"is an object\"}")).isEmpty();
    }
}

package com.bluelight.backend.api.notification.template.lint;

/**
 * Lint 검출 항목 한 건.
 *
 * @param ruleCode L1~L8 식별자 (예: {@code "L1_VARIABLE_WHITELIST"})
 * @param severity ERROR(차단) vs WARNING(경고만)
 * @param message  관리자에게 표시할 한 줄 설명 (한국어/영어 혼용 가능)
 * @param field    문제 위치 — 예: {@code "body"}, {@code "subject"}, {@code "providerTemplateName"}
 * @param detail   추가 진단 데이터 — 누락 키 리스트, 초과 글자수 등 JSON-friendly String 가능
 *
 * <p>스펙: {@code doc/Project Analysis/notification-template-manager-spec.md} §8.</p>
 */
public record LintIssue(String ruleCode,
                        Severity severity,
                        String message,
                        String field,
                        String detail) {

    public enum Severity {
        /** 저장/publish 차단. */
        ERROR,
        /** 경고만 (저장 허용, 사용자 confirm 권장). L6 가 유일. */
        WARNING
    }

    public static LintIssue error(String ruleCode, String message, String field, String detail) {
        return new LintIssue(ruleCode, Severity.ERROR, message, field, detail);
    }

    public static LintIssue warning(String ruleCode, String message, String field, String detail) {
        return new LintIssue(ruleCode, Severity.WARNING, message, field, detail);
    }
}

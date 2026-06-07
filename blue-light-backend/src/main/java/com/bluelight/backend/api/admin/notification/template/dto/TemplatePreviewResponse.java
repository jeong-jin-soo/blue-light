package com.bluelight.backend.api.admin.notification.template.dto;

import com.bluelight.backend.api.notification.template.lint.LintIssue;

import java.util.List;

/**
 * Preview 응답 — 렌더된 subject/body + 채널별 메타 (글자수, SMS segment 등).
 *
 * @param subject       렌더된 제목 (EMAIL/IN_APP 만)
 * @param body          렌더된 본문
 * @param charCount     본문 글자수
 * @param smsSegments   SMS 채널일 때 segment 수 (160자 GSM-7 기준), 그 외 채널은 null
 * @param missingKeys   본문에 사용됐으나 payload 에 없는 변수 키 (UI 경고용)
 * @param warnings      Lint warnings (L6 PII 등 — 차단은 아니지만 가시화)
 */
public record TemplatePreviewResponse(
        String subject,
        String body,
        int charCount,
        Integer smsSegments,
        List<String> missingKeys,
        List<LintIssue> warnings
) {
}

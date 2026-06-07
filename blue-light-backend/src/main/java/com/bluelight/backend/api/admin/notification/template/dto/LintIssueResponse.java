package com.bluelight.backend.api.admin.notification.template.dto;

import com.bluelight.backend.api.notification.template.lint.LintIssue;
import com.bluelight.backend.api.notification.template.lint.LintResult;

import java.util.List;

/**
 * Lint 차단 시 400 응답 body — UI 가 인라인 빨간 밑줄·필드별 에러 표시에 사용.
 *
 * <p>{@code errors} 와 {@code warnings} 를 분리해서, warnings 는 통과했더라도 UI 가 noted 로 보여준다.</p>
 */
public record LintIssueResponse(List<LintIssue> errors,
                                List<LintIssue> warnings) {

    public static LintIssueResponse from(LintResult result) {
        return new LintIssueResponse(result.errors(), result.warnings());
    }
}

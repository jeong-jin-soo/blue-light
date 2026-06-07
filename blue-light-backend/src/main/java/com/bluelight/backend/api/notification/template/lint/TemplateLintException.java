package com.bluelight.backend.api.notification.template.lint;

import lombok.Getter;

/**
 * Lint 차단 — 컨트롤러 단에서 400 Bad Request 로 매핑한다.
 *
 * <p>{@link #result} 의 {@code errors()} 가 응답 body 로 그대로 직렬화될 예정 (PR-T3).</p>
 */
@Getter
public class TemplateLintException extends RuntimeException {

    private final LintResult result;

    public TemplateLintException(LintResult result) {
        super("Template lint failed: " + result.errors().size() + " error(s)");
        this.result = result;
    }
}

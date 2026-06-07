package com.bluelight.backend.api.notification.template.lint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lint 8종 실행 결과 — 에러(차단) + 경고(통과 가능) 집계.
 *
 * <p>{@code TemplateLinter} 가 각 규칙을 순회하며 {@link #add(LintIssue)} 로 적재한다.
 * {@link #isPassed()} 는 ERROR 가 0건일 때 true.</p>
 *
 * <p>스펙: {@code doc/Project Analysis/notification-template-manager-spec.md} §8.</p>
 */
public final class LintResult {

    private final List<LintIssue> issues = new ArrayList<>();

    public void add(LintIssue issue) {
        issues.add(issue);
    }

    public List<LintIssue> errors() {
        return issues.stream().filter(i -> i.severity() == LintIssue.Severity.ERROR).toList();
    }

    public List<LintIssue> warnings() {
        return issues.stream().filter(i -> i.severity() == LintIssue.Severity.WARNING).toList();
    }

    public List<LintIssue> all() {
        return Collections.unmodifiableList(issues);
    }

    /** ERROR 0건이면 통과. WARNING 은 통과 여부에 영향 없음. */
    public boolean isPassed() {
        return errors().isEmpty();
    }

    public boolean hasWarnings() {
        return !warnings().isEmpty();
    }
}

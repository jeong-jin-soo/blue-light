package com.bluelight.backend.domain.manualemail;

/**
 * ADMIN 수동 이메일 본문 형식.
 *
 * <p>스펙: {@code doc/Project Analysis/admin-manual-email-spec.md} §4 + §9.1 (HTML 인젝션 차단).</p>
 *
 * <p>PR-1 은 {@link #PLAIN_TEXT} 만 허용. {@link #HTML} 은 enum 정의만 두고 컨트롤러에서 거부한다.
 * 향후 WYSIWYG 에디터 + XSS 방어 강화 후 활성화 예정.</p>
 */
public enum BodyFormat {
    PLAIN_TEXT,
    HTML
}

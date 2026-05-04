package com.bluelight.backend.api.admin.manualemail.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * ADMIN 수동 이메일 미리보기 응답 DTO ({@code POST /api/admin/manual-emails/preview}, PR-3).
 *
 * <p>스펙: {@code doc/Project Analysis/admin-manual-email-spec.md} §5.4.</p>
 *
 * <p>실제 발송 없이 자동 헤더("This is a manual notice...") + ADMIN 본문 + 자동 푸터("Sent by ...")
 * 가 부착된 HTML 을 반환한다. 프론트는 이 HTML 을 iframe sandbox 또는 안전한 컨테이너에 주입해
 * ADMIN 이 발송 전 모양을 확인하도록 한다.</p>
 */
@Getter
@Builder
public class ManualEmailPreviewResponse {

    /** 메일 헤더에 그대로 사용될 subject — 현재는 ADMIN 입력 그대로 (향후 카테고리 prefix 등 가능). */
    private final String renderedSubject;

    /**
     * 안전하게 렌더된 본문 HTML — 본문은 {@code HtmlUtils.htmlEscape} 후 줄바꿈만 {@code <br>} 변환되어
     * XSS 가 차단된다. 추가 HTML 마크업은 일절 허용되지 않는다 (PLAIN_TEXT 정책, 스펙 §2.2 / §9.1).
     */
    private final String renderedHtmlPreview;

    public static ManualEmailPreviewResponse of(String renderedSubject, String renderedHtmlPreview) {
        return ManualEmailPreviewResponse.builder()
                .renderedSubject(renderedSubject)
                .renderedHtmlPreview(renderedHtmlPreview)
                .build();
    }
}

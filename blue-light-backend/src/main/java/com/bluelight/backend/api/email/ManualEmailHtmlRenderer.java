package com.bluelight.backend.api.email;

import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * ADMIN 수동 이메일 HTML 렌더러 (PR-3).
 *
 * <p>스펙: {@code doc/Project Analysis/admin-manual-email-spec.md} §9.1, AC-A13, §5.4.</p>
 *
 * <p>SMTP 발송({@link SmtpEmailService#sendManualPlainTextEmail}) 과 미리보기 엔드포인트
 * ({@code POST /api/admin/manual-emails/preview}) 가 동일한 HTML 을 생성해야 한다 — 환경에 따라
 * 다르게 보이면 안 되므로 단일 진실원(SSoT)으로 본 컴포넌트에 빌더를 격리한다.</p>
 *
 * <h2>안전성</h2>
 * <ul>
 *   <li>본문 PLAIN_TEXT 는 {@link HtmlUtils#htmlEscape} 로 XSS 차단 후 줄바꿈만 {@code <br>} 로 변환.</li>
 *   <li>ADMIN 이메일도 escape 후 푸터에 주입.</li>
 *   <li>HTML 마크업 일체 허용 안 함 — 인터페이스 계약 (스펙 §2.2: HTML 본문 입력 비범위).</li>
 * </ul>
 */
@Component
public class ManualEmailHtmlRenderer {

    /**
     * 자동 헤더("This is a manual notice...") + 본문 + 자동 푸터(Sent by + 반피싱) 부착 HTML 을 생성한다.
     *
     * @param bodyText            ADMIN 입력 PLAIN_TEXT 본문 (escape 전 원문). null/empty 허용 — 빈 본문으로 렌더.
     * @param adminEmailForFooter 발송 ADMIN 이메일 — 푸터 "Sent by" 라인.
     * @return 완성된 HTML 문자열
     */
    public String render(String bodyText, String adminEmailForFooter) {
        String safeBody = escape(bodyText == null ? "" : bodyText)
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace("\n", "<br>");
        String safeAdminEmail = escape(adminEmailForFooter == null ? "" : adminEmailForFooter);
        return """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"></head>
                <body style="font-family: Arial, sans-serif; background-color: #f4f6f9; margin: 0; padding: 20px;">
                  <div style="max-width: 600px; margin: 0 auto; background-color: #ffffff; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.1);">
                    <div style="background-color: #1a3a5c; padding: 24px; text-align: center;">
                      <h1 style="color: #ffffff; margin: 0; font-size: 24px;">LicenseKaki</h1>
                    </div>
                    <div style="padding: 32px 24px;">
                      <div style="background-color: #eff6ff; border: 1px solid #bfdbfe; border-radius: 6px; padding: 12px 16px; margin-bottom: 20px;">
                        <p style="color: #1e3a8a; font-size: 13px; line-height: 1.5; margin: 0;">
                          This is a manual notice from a LicenseKaki administrator.
                        </p>
                      </div>
                      <div style="color: #333333; line-height: 1.6; font-size: 15px;">
                        %s
                      </div>
                      <hr style="border: none; border-top: 1px solid #eee; margin: 28px 0 16px;">
                      <p style="color: #6b7280; font-size: 12px; line-height: 1.5; margin: 0 0 6px;">
                        Sent by: <strong>%s</strong>
                      </p>
                      <p style="color: #aaaaaa; font-size: 12px; line-height: 1.5; margin: 0;">
                        This message was sent manually from LicenseKaki. Our only sender domain is
                        <strong>@licensekaki.sg</strong>. We will never ask for your password, OTP, or PIN by
                        email. If anything looks suspicious, sign in directly from licensekaki.sg or contact
                        support@licensekaki.sg.
                      </p>
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(safeBody, safeAdminEmail);
    }

    private static String escape(String s) {
        return s == null ? "" : HtmlUtils.htmlEscape(s);
    }
}

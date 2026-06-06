package com.bluelight.backend.api.notification.template;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 알림 템플릿 변수 치환기.
 *
 * <p>지원 문법 — <code>{{var}}</code> 형식. payload Map 의 키와 일치하는 경우 값으로 치환,
 * 키 없음 시 빈 문자열로 대체 (변수 누락이 발송 실패로 이어지지 않도록 lenient). 누락 시 WARN
 * 로그를 남기는 것은 호출 측(Orchestrator)이 책임.</p>
 *
 * <p>예:
 * <pre>
 *   template: "Payment of S${{amount}} due for APP-{{applicationCode}}."
 *   payload : {amount=185.00, applicationCode=2026-00428}
 *   render  : "Payment of S$185.00 due for APP-2026-00428."
 * </pre>
 *
 * <p>WhatsApp Meta Cloud API 는 본문 자체보다는 <em>variables_json</em> 배열로 변수만 전송한다.
 * 본 렌더러의 결과 본문은 인앱·이메일·WhatsApp 의 fallback/preview 용. WhatsApp 발송 시
 * payload 변수 자체를 BSP 호출에 직접 전달한다 (WhatsApp 어댑터 책임, PR-1B).</p>
 */
@Component
public class TemplateRenderer {

    /** <code>{{key}}</code> 패턴 — 공백 허용, 영문/숫자/밑줄/하이픈/점 키 지원. */
    private static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.\\-]+)\\s*}}");

    /**
     * 안전장치 패턴 — 치환 후에도 남은 모든 <code>{{...}}</code> 잔여 플레이스홀더.
     * 카피북 optional 표기 <code>{{managerNote?}}</code>(키에 {@code ?} 포함)처럼 VAR_PATTERN 에
     * 매칭되지 않아 통과한 토큰까지 제거해 <strong>고객에게 {{...}} 가 절대 노출되지 않도록</strong> 보장한다.
     */
    private static final Pattern LEFTOVER_PATTERN = Pattern.compile("\\{\\{[^{}]*}}");

    /**
     * 본문 변수 치환.
     *
     * @param template 본문 템플릿 (예: {@code notification_templates.body_text})
     * @param payload  변수 슬롯 값 (null 허용 — null/missing 키는 빈 문자열로 치환)
     */
    public String render(String template, Map<String, String> payload) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Map<String, String> safePayload = payload != null ? payload : Map.of();
        Matcher m = VAR_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String value = safePayload.getOrDefault(key, "");
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        // 안전장치: 치환되지 않고 남은 {{...}} (optional `?` 토큰·오타 등) 를 모두 제거 → 고객 노출 방지.
        return LEFTOVER_PATTERN.matcher(sb).replaceAll("");
    }
}

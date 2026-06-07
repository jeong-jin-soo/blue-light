package com.bluelight.backend.api.notification.template.lint;

import com.bluelight.backend.domain.notification.NotificationCategory;
import com.bluelight.backend.domain.notification.NotificationChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 차단형 lint 8종 — 스펙 §8 의 단일 진입점.
 *
 * <pre>
 *   L1 변수 화이트리스트       — 미정의 {{var}} 발견 → ERROR
 *   L2 SMS 160 자             — prefix 포함 161 자 이상 → ERROR
 *   L3 MARKETING [ADV] prefix → ERROR
 *   L4 MARKETING opt-out 변수 → ERROR
 *   L5 PayNow 리터럴 박제      → ERROR (UEN/계좌번호 정규식)
 *   L6 PII subject/SMS        → WARNING (저장 허용, confirm 권장)
 *   L7 EMAIL footer 토큰       → ERROR ({{footerBlock}} 누락)
 *   L8 WHATSAPP 위치 변수      → ERROR (이름 변수 차단)
 * </pre>
 *
 * <p>{@link #lint(LintInput)} 는 모든 규칙을 누적 적용한 {@link LintResult} 를 반환한다 —
 * 첫 ERROR 에서 단락(short-circuit)하지 않음. UI 에서 사용자에게 모든 문제를 한 번에 보여주기 위해.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TemplateLinter {

    // ───────── SMS 정책 ─────────
    private static final String SMS_PREFIX = "[LicenseKaki] ";
    private static final int SMS_MAX_CHARS = 160;

    // ───────── MARKETING 정책 ─────────
    private static final String ADV_PREFIX = "[ADV]";
    private static final String OPT_OUT_TOKEN = "{{optOutUrl}}";

    // ───────── PayNow 리터럴 정규식 (L5) ─────────
    /** 싱가포르 UEN (예: 201912345A, 201912345AB, T19LL1234A) + 8~10 자리 숫자 + 단일 대문자. */
    private static final Pattern UEN_PATTERN = Pattern.compile("(?<![A-Za-z0-9])\\d{8,10}[A-Z](?![A-Za-z0-9])");
    /** Singapore registered business UEN (T/S/R prefix). */
    private static final Pattern UEN_T_PATTERN = Pattern.compile("(?<![A-Za-z0-9])[TSR]\\d{2}[A-Z]{2}\\d{4}[A-Z](?![A-Za-z0-9])");

    // ───────── PII keyword (L6 warning) ─────────
    private static final Set<String> PII_VAR_KEYS = Set.of(
            "applicantName",
            "installationAddress",
            "address",
            "licenceNumber",
            "licenseNumber",
            "amount",
            "nric",
            "phoneNumber"
    );

    // ───────── EMAIL footer 토큰 (L7) ─────────
    private static final String FOOTER_TOKEN = "{{footerBlock}}";

    // ───────── WHATSAPP 위치 변수 (L8) ─────────
    private static final Pattern WA_POSITIONAL_PATTERN = Pattern.compile("\\{\\{\\s*(\\d+)\\s*}}");
    private static final Pattern WA_ANY_NAMED_PATTERN = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_.\\-]*)\\s*}}");

    private final TemplateVariableValidator variableValidator;

    public LintResult lint(LintInput input) {
        LintResult result = new LintResult();

        checkVariableWhitelist(input, result);     // L1
        checkSmsLength(input, result);             // L2
        checkMarketingAdvPrefix(input, result);    // L3
        checkMarketingOptOut(input, result);       // L4
        checkPaynowLiteral(input, result);         // L5
        checkPiiInSubjectOrSms(input, result);     // L6 (WARNING)
        checkEmailFooterToken(input, result);      // L7
        checkWhatsappPositional(input, result);    // L8

        if (!result.isPassed()) {
            log.debug("Lint failed for {} ({}): {} errors, {} warnings",
                    input.templateCode(), input.channel(),
                    result.errors().size(), result.warnings().size());
        }
        return result;
    }

    // ============================================================
    // L1 — Variable whitelist
    // ============================================================
    private void checkVariableWhitelist(LintInput input, LintResult result) {
        Set<String> usedInBody = variableValidator.extractVariables(input.body());
        Set<String> usedInSubject = variableValidator.extractVariables(input.subject());
        Set<String> used = new LinkedHashSet<>();
        used.addAll(usedInBody);
        used.addAll(usedInSubject);

        Set<String> declared = variableValidator.parseVariableSet(input.declaredVariablesJson());
        Set<String> allowed = variableValidator.parseVariableSet(input.allowedVariablesJson());

        // WHATSAPP 은 위치 변수만 — L8 에서 따로 검증, L1 은 EMAIL/IN_APP/SMS 에 한정
        if (input.channel() == NotificationChannel.WHATSAPP) {
            return;
        }

        // 시스템 주입 토큰은 화이트리스트 면제
        used.remove("footerBlock");
        used.remove("optOutUrl");
        used.remove("paynowUen");
        used.remove("paynowReference");
        used.remove("paynowAccountName");

        Set<String> union = new LinkedHashSet<>();
        union.addAll(declared);
        union.addAll(allowed);

        Set<String> undefined = new LinkedHashSet<>();
        for (String v : used) {
            if (!union.contains(v)) {
                undefined.add(v);
            }
        }
        if (!undefined.isEmpty()) {
            result.add(LintIssue.error(
                    "L1_VARIABLE_WHITELIST",
                    "미정의 변수가 본문에 사용됨: " + String.join(", ", undefined),
                    "body",
                    String.join(",", undefined)
            ));
        }
    }

    // ============================================================
    // L2 — SMS 160 chars (with [LicenseKaki] prefix)
    // ============================================================
    private void checkSmsLength(LintInput input, LintResult result) {
        if (input.channel() != NotificationChannel.SMS) {
            return;
        }
        String body = input.body() == null ? "" : input.body();
        String withPrefix = body.startsWith(SMS_PREFIX) ? body : SMS_PREFIX + body;
        if (withPrefix.length() > SMS_MAX_CHARS) {
            result.add(LintIssue.error(
                    "L2_SMS_LENGTH",
                    "SMS 본문이 160 자(prefix 포함) 를 초과합니다: " + withPrefix.length() + " 자",
                    "body",
                    String.valueOf(withPrefix.length())
            ));
        }
    }

    // ============================================================
    // L3 — MARKETING subject [ADV] prefix
    // ============================================================
    private void checkMarketingAdvPrefix(LintInput input, LintResult result) {
        if (input.category() != NotificationCategory.MARKETING) {
            return;
        }
        if (input.channel() != NotificationChannel.EMAIL) {
            return; // subject 는 EMAIL 전용
        }
        String subject = input.subject() == null ? "" : input.subject().trim();
        if (!subject.startsWith(ADV_PREFIX)) {
            result.add(LintIssue.error(
                    "L3_MARKETING_ADV_PREFIX",
                    "MARKETING 카테고리의 subject 는 '" + ADV_PREFIX + "' 로 시작해야 합니다 (Spam Control Act §13).",
                    "subject",
                    subject
            ));
        }
    }

    // ============================================================
    // L4 — MARKETING body must include {{optOutUrl}}
    // ============================================================
    private void checkMarketingOptOut(LintInput input, LintResult result) {
        if (input.category() != NotificationCategory.MARKETING) {
            return;
        }
        String body = input.body() == null ? "" : input.body();
        if (!body.contains(OPT_OUT_TOKEN)) {
            result.add(LintIssue.error(
                    "L4_MARKETING_OPT_OUT",
                    "MARKETING 카테고리의 본문에는 " + OPT_OUT_TOKEN + " 변수가 반드시 포함되어야 합니다.",
                    "body",
                    null
            ));
        }
    }

    // ============================================================
    // L5 — PayNow UEN/계좌번호 리터럴 박제 차단
    // ============================================================
    private void checkPaynowLiteral(LintInput input, LintResult result) {
        String body = input.body() == null ? "" : input.body();
        if (UEN_PATTERN.matcher(body).find() || UEN_T_PATTERN.matcher(body).find()) {
            result.add(LintIssue.error(
                    "L5_PAYNOW_LITERAL",
                    "PayNow UEN/계좌번호로 보이는 리터럴이 발견되었습니다. {{paynowUen}} 변수를 사용하세요.",
                    "body",
                    null
            ));
        }
    }

    // ============================================================
    // L6 — PII in subject or SMS body (WARNING)
    // ============================================================
    private void checkPiiInSubjectOrSms(LintInput input, LintResult result) {
        Set<String> subjectVars = variableValidator.extractVariables(input.subject());
        Set<String> smsVars = (input.channel() == NotificationChannel.SMS)
                ? variableValidator.extractVariables(input.body())
                : Set.of();

        Set<String> piiSubject = new LinkedHashSet<>(subjectVars);
        piiSubject.retainAll(PII_VAR_KEYS);
        if (!piiSubject.isEmpty()) {
            result.add(LintIssue.warning(
                    "L6_PII_SUBJECT",
                    "subject 에 PII 변수 사용 감지: " + String.join(", ", piiSubject)
                            + ". PDPA 가이드 — public inbox 노출 위험.",
                    "subject",
                    String.join(",", piiSubject)
            ));
        }

        Set<String> piiSms = new LinkedHashSet<>(smsVars);
        piiSms.retainAll(PII_VAR_KEYS);
        if (!piiSms.isEmpty()) {
            result.add(LintIssue.warning(
                    "L6_PII_SMS",
                    "SMS body 에 PII 변수 사용 감지: " + String.join(", ", piiSms)
                            + ". 단축 URL 로 유도하는 방식을 권장.",
                    "body",
                    String.join(",", piiSms)
            ));
        }
    }

    // ============================================================
    // L7 — EMAIL footer token required
    // ============================================================
    private void checkEmailFooterToken(LintInput input, LintResult result) {
        if (input.channel() != NotificationChannel.EMAIL) {
            return;
        }
        String body = input.body() == null ? "" : input.body();
        if (!body.contains(FOOTER_TOKEN)) {
            result.add(LintIssue.error(
                    "L7_EMAIL_FOOTER",
                    "EMAIL 본문에는 " + FOOTER_TOKEN + " 토큰이 반드시 포함되어야 합니다 (anti-phishing footer 시스템 주입 위치).",
                    "body",
                    null
            ));
        }
    }

    // ============================================================
    // L8 — WHATSAPP positional variables only ({{1}}, {{2}}, ...)
    // ============================================================
    private void checkWhatsappPositional(LintInput input, LintResult result) {
        if (input.channel() != NotificationChannel.WHATSAPP) {
            return;
        }
        String body = input.body() == null ? "" : input.body();
        Set<String> named = new LinkedHashSet<>();
        java.util.regex.Matcher m = WA_ANY_NAMED_PATTERN.matcher(body);
        while (m.find()) {
            named.add(m.group(1));
        }
        // 시스템 토큰도 WHATSAPP 본문엔 허용 안 함 — Meta 가 위치 변수만 받음
        if (!named.isEmpty()) {
            result.add(LintIssue.error(
                    "L8_WHATSAPP_POSITIONAL",
                    "WHATSAPP 본문에는 위치 변수 {{1}}, {{2}}, ... 만 허용됩니다. 이름 변수 발견: "
                            + String.join(", ", named),
                    "body",
                    String.join(",", named)
            ));
        }
        // 추가: 위치 변수가 한 개도 없으면 안 막지만, providerTemplateName 은 필수
        if (input.providerTemplateName() == null || input.providerTemplateName().isBlank()) {
            result.add(LintIssue.error(
                    "L8_WHATSAPP_POSITIONAL",
                    "WHATSAPP 채널은 Meta 사전 승인된 providerTemplateName 이 필수입니다.",
                    "providerTemplateName",
                    null
            ));
        }
    }
}

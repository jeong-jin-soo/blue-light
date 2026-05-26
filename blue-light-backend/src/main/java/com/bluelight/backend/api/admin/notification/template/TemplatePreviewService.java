package com.bluelight.backend.api.admin.notification.template;

import com.bluelight.backend.api.admin.notification.template.dto.TemplatePreviewResponse;
import com.bluelight.backend.api.notification.template.TemplateRenderer;
import com.bluelight.backend.api.notification.template.lint.LintInput;
import com.bluelight.backend.api.notification.template.lint.LintResult;
import com.bluelight.backend.api.notification.template.lint.TemplateLinter;
import com.bluelight.backend.api.notification.template.lint.TemplateVariableValidator;
import com.bluelight.backend.domain.notification.NotificationCatalog;
import com.bluelight.backend.domain.notification.NotificationCatalogRepository;
import com.bluelight.backend.domain.notification.NotificationChannel;
import com.bluelight.backend.domain.notification.NotificationTemplate;
import com.bluelight.backend.domain.notification.NotificationTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 템플릿 Preview — 변수 sample 입력 → 실제 렌더링 결과 + 메타 반환 (PR-T4).
 *
 * <p>실발송과 동일한 {@link TemplateRenderer} 를 사용하여 발송 결과와 일치 보장.
 * Lint warnings(L6 PII 등) 를 함께 반환하여 UI 가 가시화한다.
 * <b>저장하지 않음, 순수 read-only.</b></p>
 *
 * <p>SMS segment 계산은 GSM-7 기준 160자 (실제 multi-segment 는 153자 단위이나, lint L2 가
 * prefix 포함 160자 룰을 사용하므로 본 응답에서도 동일 기준 사용).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TemplatePreviewService {

    private static final int SMS_SEGMENT_SIZE = 160;

    /** TemplateLinter 와 동일 — 시스템 주입 토큰은 missingKeys 에서 면제. */
    private static final Set<String> SYSTEM_TOKENS = Set.of(
            "footerBlock", "optOutUrl", "paynowUen", "paynowReference", "paynowAccountName"
    );

    private final NotificationTemplateRepository templateRepository;
    private final NotificationCatalogRepository catalogRepository;
    private final TemplateRenderer renderer;
    private final TemplateLinter linter;
    private final TemplateVariableValidator variableValidator;

    /**
     * @param templateSeq 대상 템플릿
     * @param payload     변수 sample 값. 비어있어도 lint warnings + missing keys 가 채워짐.
     */
    @Transactional(readOnly = true)
    public TemplatePreviewResponse preview(Long templateSeq, Map<String, String> payload) {
        NotificationTemplate template = templateRepository.findById(templateSeq)
                .orElseThrow(() -> new NotificationTemplateAdminService.TemplateNotFoundException(templateSeq));

        Map<String, String> safePayload = payload != null ? payload : Map.of();

        // 1) 렌더 — 발송 경로와 동일한 TemplateRenderer 사용
        String renderedBody = renderer.render(template.getBodyText(), safePayload);
        String renderedSubject = renderer.render(template.getSubject(), safePayload);

        // 2) Missing keys — 본문/제목에 사용됐으나 payload 에 없는 변수
        Set<String> usedKeys = new LinkedHashSet<>();
        usedKeys.addAll(variableValidator.extractVariables(template.getBodyText()));
        usedKeys.addAll(variableValidator.extractVariables(template.getSubject()));
        List<String> missingKeys = new ArrayList<>();
        for (String key : usedKeys) {
            if (SYSTEM_TOKENS.contains(key)) continue;
            if (!safePayload.containsKey(key) || safePayload.get(key) == null || safePayload.get(key).isBlank()) {
                missingKeys.add(key);
            }
        }

        // 3) Lint warnings — 카탈로그 메타 합쳐서 다시 실행
        Optional<NotificationCatalog> catalog = catalogRepository.findByTemplateCode(template.getTemplateCode());
        LintInput lintInput = new LintInput(
                template.getTemplateCode(),
                template.getChannel(),
                template.getSubject(),
                template.getBodyText(),
                template.getCategory(),
                template.getProviderTemplateName(),
                template.getVariablesJson(),
                catalog.map(NotificationCatalog::getAllowedVariablesJson).orElse(null),
                catalog.map(NotificationCatalog::getRequiredTokensJson).orElse(null)
        );
        LintResult lintResult = linter.lint(lintInput);

        // 4) SMS segment 계산
        Integer segments = template.getChannel() == NotificationChannel.SMS
                ? smsSegments(renderedBody)
                : null;

        return new TemplatePreviewResponse(
                renderedSubject,
                renderedBody,
                renderedBody.length(),
                segments,
                missingKeys,
                lintResult.warnings()
        );
    }

    private static int smsSegments(String body) {
        if (body == null || body.isEmpty()) return 0;
        return (int) Math.ceil((double) body.length() / SMS_SEGMENT_SIZE);
    }
}

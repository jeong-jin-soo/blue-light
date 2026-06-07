package com.bluelight.backend.api.notification.template.lint;

import com.bluelight.backend.domain.notification.NotificationCategory;
import com.bluelight.backend.domain.notification.NotificationChannel;

/**
 * Lint 입력 — 본문/메타와 카탈로그 메타(허용 변수·강제 토큰) 동시에 받는다.
 *
 * @param templateCode          템플릿 ID (예: {@code A-17})
 * @param channel               IN_APP / EMAIL / SMS / WHATSAPP
 * @param subject               EMAIL 전용, 다른 채널은 null 가능
 * @param body                  본문 (필수)
 * @param category              카테고리 (nullable — 신규 코드는 미설정 허용)
 * @param providerTemplateName  WHATSAPP Meta 등록명, 다른 채널은 null
 * @param declaredVariablesJson 템플릿 자체가 선언한 variables_json (JSON 배열)
 * @param allowedVariablesJson  카탈로그가 허용한 변수 화이트리스트 (JSON 배열)
 * @param requiredTokensJson    카탈로그가 강제하는 토큰 리터럴 (예: {@code ["{{paynowUen}}"]})
 */
public record LintInput(String templateCode,
                        NotificationChannel channel,
                        String subject,
                        String body,
                        NotificationCategory category,
                        String providerTemplateName,
                        String declaredVariablesJson,
                        String allowedVariablesJson,
                        String requiredTokensJson) {
}

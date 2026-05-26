package com.bluelight.backend.api.admin.notification.template.dto;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * Test-send 요청 — payload 변수만. 수신자는 항상 본인(SecurityContext), 채널은 템플릿의 채널 (EMAIL 만 지원).
 */
public record TemplateTestSendRequest(
        @NotNull Map<String, String> payload
) {
    public Map<String, String> payloadOrEmpty() {
        return payload != null ? payload : Map.of();
    }
}

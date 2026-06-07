package com.bluelight.backend.api.admin.notification.template.dto;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

/** Preview 요청 — payload map (변수 sample 값). */
public record TemplatePreviewRequest(
        @NotNull Map<String, String> payload
) {
    public Map<String, String> payloadOrEmpty() {
        return payload != null ? payload : Map.of();
    }
}
